/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.finalization

import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.timer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import linea.contract.l1.LineaRollupSmartContractClientReadOnly
import linea.domain.BlockParameter
import linea.domain.BlockParameter.Companion.toBlockParameter
import linea.ethapi.EthApiClient
import maru.consensus.state.FinalizationProvider
import maru.consensus.state.FinalizationState
import maru.core.BeaconBlockBody
import tech.pegasys.teku.infrastructure.async.SafeFuture

class LineaFinalizationProvider(
  private val lineaContract: LineaRollupSmartContractClientReadOnly,
  private val l2EthApi: EthApiClient,
  private val pollingUpdateInterval: Duration,
  private val l1HighestBlock: BlockParameter = BlockParameter.Tag.FINALIZED,
) : FinalizationProvider {
  private data class BlockHeader(
    val blockNumber: ULong,
    val hash: ByteArray,
  )

  private val lastFinalizedBlock: AtomicReference<BlockHeader>

  init {
    // initialize the last finalized block with the latest finalized block on L2
    l2EthApi
      .getBlockByNumberWithoutTransactionsData(BlockParameter.Tag.FINALIZED)
      .get()
      .let { block ->
        lastFinalizedBlock =
          AtomicReference(
            BlockHeader(
              blockNumber = block.number,
              hash = block.hash,
            ),
          )
      }
  }

  private val poller =
    timer(
      name = "l1-finalization-poller",
      daemon = true,
      initialDelay = 0.seconds.inWholeMilliseconds,
      period = pollingUpdateInterval.inWholeMilliseconds,
    ) {
      update()
    }

  fun stop() {
    poller.cancel()
  }

  private fun update() {
    lastFinalizedBlock.set(getFinalizedBlockOnL1().get())
  }

  private fun getFinalizedBlockOnL1(): SafeFuture<BlockHeader> =
    lineaContract
      .finalizedL2BlockNumber(l1HighestBlock)
      .thenCompose { finalizedBlockNumber ->
        l2EthApi
          .findBlockByNumberWithoutTransactionsData(finalizedBlockNumber.toBlockParameter())
          .thenCompose { block ->
            if (block == null) {
              // If this node is behind (syncing) won't have the block locally to retrieve it's hash
              // until it catches up, we will default to the last known finalized block locally
              l2EthApi
                .getBlockByNumberWithoutTransactionsData(BlockParameter.Tag.FINALIZED)
            } else {
              SafeFuture.completedFuture(block)
            }
          }
      }.thenApply { block ->
        BlockHeader(
          blockNumber = block.number,
          hash = block.hash,
        )
      }

  override fun invoke(beaconBlock: BeaconBlockBody): FinalizationState =
    lastFinalizedBlock.get().let { finalizedBlock ->
      FinalizationState(
        safeBlockHash = finalizedBlock.hash,
        finalizedBlockHash = finalizedBlock.hash,
      )
    }
}
