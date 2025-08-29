/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.consensus.delegated

import java.lang.Exception
import java.util.Timer
import java.util.UUID
import kotlin.concurrent.timerTask
import kotlin.time.Duration.Companion.seconds
import maru.config.consensus.delegated.ElDelegatedConfig
import maru.consensus.ForkSpec
import maru.consensus.ProtocolFactory
import maru.core.Protocol
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameter

class ElDelegatedConsensusFactory(
  private val ethereumJsonRpcClient: Web3j,
  private val postTtdProtocolFactory: ProtocolFactory,
) : ProtocolFactory {
  override fun create(forkSpec: ForkSpec): ElDelegatedConsensus =
    ElDelegatedConsensus(
      ethereumJsonRpcClient = ethereumJsonRpcClient,
      postTtdProtocolFactory = postTtdProtocolFactory,
      forkSpec = forkSpec,
    )
}

class ElDelegatedConsensus(
  private val ethereumJsonRpcClient: Web3j,
  private val postTtdProtocolFactory: ProtocolFactory,
  private val forkSpec: ForkSpec,
  private val timerFactory: (String, Boolean) -> Timer = { name, isDaemon ->
    Timer(
      "$name-${UUID.randomUUID()}",
      isDaemon,
    )
  },
) : Protocol {
  private val log: Logger = LogManager.getLogger(this.javaClass)

  private var poller: Timer? = null
  private var postTtdProtocol: Protocol? = null
  private var ttdReached: Boolean = false

  private fun pollTask() {
    val elDelegatedConfig = forkSpec.configuration as ElDelegatedConfig
    try {
      // Skip if TTD already reached and protocol instantiated
      if (ttdReached && postTtdProtocol != null) {
        return
      }

      // Get the latest block from EL
      val latestBlock =
        ethereumJsonRpcClient
          .ethGetBlockByNumber(DefaultBlockParameter.valueOf("latest"), false)
          .send()
          .block

      if (latestBlock == null) {
        log.warn("Failed to retrieve latest block from EL")
        return
      }

      val currentBlockNumber = latestBlock.number.toLong()
      log.debug(
        "Current EL block number: {}, TTD block number: {}",
        currentBlockNumber,
        elDelegatedConfig.switchBlockNumber,
      )

      // Check if we've reached the TTD block number
      if (currentBlockNumber > elDelegatedConfig.switchBlockNumber.toLong()) {
        log.info("TTD reached at block number: {}. Transitioning to post-TTD protocol.", currentBlockNumber)
        val postTtdForkSpec =
          ForkSpec(
            timestampSeconds = forkSpec.timestampSeconds,
            blockTimeSeconds = forkSpec.blockTimeSeconds,
            configuration = elDelegatedConfig.postTtdConfig,
          )
        transitionToPostTtdProtocol(postTtdForkSpec)
      }
    } catch (e: Exception) {
      log.error("Error during EL block polling", e)
    }
  }

  @Synchronized
  private fun transitionToPostTtdProtocol(postTtdForkSpec: ForkSpec) {
    if (ttdReached) {
      return // Already transitioned
    }

    try {
      log.info("Creating post-TTD protocol with fork spec: {}", postTtdForkSpec)
      postTtdProtocol = postTtdProtocolFactory.create(postTtdForkSpec)
      postTtdProtocol?.start()
      ttdReached = true
      log.info("Post-TTD protocol started successfully")
    } catch (e: Exception) {
      log.error("Failed to start post-TTD protocol", e)
      throw e
    }
  }

  override fun start() {
    synchronized(this) {
      if (poller != null) {
        return
      }
      log.debug("Starting ElDelegatedConsensus with polling interval: {} seconds", forkSpec.blockTimeSeconds)
      poller = timerFactory("ElDelegatedConsensus", true)
      poller!!.scheduleAtFixedRate(
        timerTask {
          try {
            pollTask()
          } catch (e: Exception) {
            log.warn("ElDelegatedConsensus poll task exception", e)
          }
        },
        0,
        forkSpec.blockTimeSeconds
          .toInt()
          .seconds.inWholeMilliseconds,
      )
    }
  }

  override fun stop() {
    synchronized(this) {
      // Stop the polling timer
      if (poller != null) {
        log.debug("Stopping ElDelegatedConsensus poller")
        poller?.cancel()
        poller = null
      }

      // Stop the post-TTD protocol if it was started
      if (postTtdProtocol != null) {
        log.debug("Stopping post-TTD protocol")
        try {
          postTtdProtocol?.stop()
        } catch (e: Exception) {
          log.warn("Error stopping post-TTD protocol", e)
        }
        postTtdProtocol = null
      }

      ttdReached = false
    }
  }
}
