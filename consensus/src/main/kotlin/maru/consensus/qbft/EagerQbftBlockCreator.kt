/*
   Copyright 2025 Consensys Software Inc.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */
package maru.consensus.qbft

import java.time.Clock
import kotlin.time.Duration
import maru.consensus.MetadataProvider
import maru.consensus.NextBlockTimestampProvider
import maru.consensus.qbft.adapters.toBeaconBlockHeader
import maru.consensus.state.FinalizationState
import maru.core.BeaconBlockHeader
import maru.core.Validator
import maru.executionlayer.manager.ExecutionLayerManager
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.hyperledger.besu.consensus.qbft.core.types.QbftBlock
import org.hyperledger.besu.consensus.qbft.core.types.QbftBlockCreator
import org.hyperledger.besu.consensus.qbft.core.types.QbftBlockHeader
import org.hyperledger.besu.crypto.SECPSignature

/**
 * Responsible for QBFT block creation. As opposed to DelayedQbftBlockCreator, Eager one will send the FCU request to
 * the execution client to start the block building process and will wait for the required time until a block is built,
 * blocking the thread
 */
class EagerQbftBlockCreator(
  private val manager: ExecutionLayerManager,
  private val delegate: QbftBlockCreator,
  private val finalizationStateProvider: (BeaconBlockHeader) -> FinalizationState,
  private val blockBuilderIdentity: Validator,
  private val metadataProvider: MetadataProvider,
  private val nextBlockTimestampProvider: NextBlockTimestampProvider,
  private val config: Config,
  private val clock: Clock,
) : QbftBlockCreator {
  private val log: Logger = LogManager.getLogger(this.javaClass)

  data class Config(
    val communicationMargin: Duration,
  )

  override fun createBlock(
    headerTimeStampSeconds: Long,
    parentHeader: QbftBlockHeader,
  ): QbftBlock {
    val beaconBlockHeader = parentHeader.toBeaconBlockHeader()
    val finalizedState = finalizationStateProvider(beaconBlockHeader)
    manager
      .setHeadAndStartBlockBuilding(
        headHash = metadataProvider.getLatestBlockMetadata().blockHash,
        safeHash = finalizedState.safeBlockHash,
        finalizedHash = finalizedState.finalizedBlockHash,
        nextBlockTimestamp = headerTimeStampSeconds,
        feeRecipient = blockBuilderIdentity.address,
      ).get()
    val sleepTime = computeSleepDurationMilliseconds(headerTimeStampSeconds)
    log.debug("Block building has started, sleeping for {} milliseconds", sleepTime)
    Thread.sleep(sleepTime)
    log.debug("Block building has finished, time to collect block building results")
    return delegate.createBlock(headerTimeStampSeconds, parentHeader)
  }

  override fun createSealedBlock(
    block: QbftBlock,
    roundNumber: Int,
    commitSeals: MutableCollection<SECPSignature>,
  ): QbftBlock = DelayedQbftBlockCreator.createSealedBlock(block, roundNumber, commitSeals)

  private fun computeSleepDurationMilliseconds(headerTimeStampSeconds: Long): Long =
    (nextBlockTimestampProvider.nextTargetBlockUnixTimestamp(headerTimeStampSeconds)) * 1000 - clock.millis() -
      config.communicationMargin.inWholeMilliseconds
}
