/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.consensus.qbft

import java.lang.Exception
import java.util.Timer
import java.util.UUID
import kotlin.concurrent.timerTask
import kotlin.time.Duration.Companion.seconds
import maru.consensus.DifficultyAwareQbftConfig
import maru.consensus.ForkSpec
import maru.consensus.ProtocolFactory
import maru.core.Protocol
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameter

class DifficultyAwareQbftFactory(
  private val ethereumJsonRpcClient: Web3j,
  private val postTtdProtocolFactory: ProtocolFactory,
) : ProtocolFactory {
  override fun create(forkSpec: ForkSpec): DifficultyAwareQbft =
    DifficultyAwareQbft(
      ethereumJsonRpcClient = ethereumJsonRpcClient,
      postTtdProtocolFactory = postTtdProtocolFactory,
      forkSpec = forkSpec,
    )
}

class DifficultyAwareQbft(
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

  private fun pollTask() {
    val difficultyAwareQbftConfig = forkSpec.configuration as DifficultyAwareQbftConfig
    try {
      if (postTtdProtocol != null) {
        // Maybe it was stopped and started after the TTD was reached and poller stopped once
        stopPoller()
        return
      }

      val latestBlock =
        ethereumJsonRpcClient
          .ethGetBlockByNumber(DefaultBlockParameter.valueOf("latest"), false)
          .send()
          .block

      if (latestBlock == null) {
        log.warn("Failed to retrieve latest block from EL")
        return
      }

      val totalDifficulty = latestBlock.totalDifficulty.toLong()
      log.debug(
        "Current elBlockNumber={}, totalDifficulty={}, terminalTotalDifficulty={}",
        latestBlock.number,
        totalDifficulty,
        difficultyAwareQbftConfig.terminalTotalDifficulty,
      )

      if (totalDifficulty >= difficultyAwareQbftConfig.terminalTotalDifficulty.toLong()) {
        log.info("TTD reached at elBlockNumber={}. Transitioning to post-TTD protocol.", latestBlock.number)
        val postTtdForkSpec =
          ForkSpec(
            timestampSeconds = forkSpec.timestampSeconds,
            blockTimeSeconds = forkSpec.blockTimeSeconds,
            configuration = difficultyAwareQbftConfig.postTtdConfig,
          )
        transitionToPostTtdProtocol(postTtdForkSpec)
        stopPoller()
      }
    } catch (e: Exception) {
      log.error("Error during EL block polling", e)
    }
  }

  @Synchronized
  private fun transitionToPostTtdProtocol(postTtdForkSpec: ForkSpec) {
    if (postTtdProtocol != null) {
      throw IllegalStateException("This protocol is supposed to be stopped after reaching TTD")
    }

    try {
      log.info("Creating post-TTD protocol forkSpec={}", postTtdForkSpec)
      postTtdProtocol = postTtdProtocolFactory.create(postTtdForkSpec)
      postTtdProtocol?.start()
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
      log.debug("Starting DifficultyAwareQbft with pollingInterval={} seconds", forkSpec.blockTimeSeconds)
      poller = timerFactory("DifficultyAwareQbft", true)
      poller!!.scheduleAtFixedRate(
        timerTask {
          try {
            pollTask()
          } catch (e: Exception) {
            log.warn("DifficultyAwareQbft poll task exception", e)
          }
        },
        0,
        forkSpec.blockTimeSeconds
          .toInt()
          .seconds.inWholeMilliseconds,
      )
      postTtdProtocol?.start()
    }
  }

  override fun pause() {
    synchronized(this) {
      if (poller != null) {
        stopPoller()
      }

      if (postTtdProtocol != null) {
        log.debug("Stopping post-TTD protocol")
        try {
          postTtdProtocol?.pause()
        } catch (e: Exception) {
          log.warn("Error stopping post-TTD protocol", e)
        }
      }
    }
  }

  override fun close() {
    synchronized(this) {
      pause()
      postTtdProtocol?.close()
    }
  }

  private fun stopPoller() {
    log.debug("Stopping DifficultyAwareQbft poller")
    poller?.cancel()
    poller = null
  }
}
