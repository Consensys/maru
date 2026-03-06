/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.consensus.qbft

import maru.consensus.ValidatorProvider
import maru.database.BeaconChain
import maru.executionlayer.manager.ExecutionLayerManager
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.hyperledger.besu.consensus.common.bft.blockcreation.ProposerSelector
import org.hyperledger.besu.consensus.qbft.core.types.QbftBlockCreatorFactory
import org.hyperledger.besu.consensus.qbft.core.types.QbftBlockCreator as BesuQbftBlockCreator

/**
 * Maru's QbftBlockCreator factory.
 *
 * Every validator pre-builds the next block via a combination of two triggers:
 * - On COMMIT of block N: the round-0 proposer for block N+1 starts building (via
 *   BlockBuildingBeaconBlockImporter).
 * - At the beginning of round R: the round-(R+1) proposer starts building (via
 *   QbftEventMultiplexer.onRoundStarted).
 *
 * Therefore, when any proposer is elected in any round, a pre-built payload is already
 * available and DelayedQbftBlockCreator can call engine_getPayload immediately.
 *
 * Genesis+1 exception: the round-0 proposer has no pre-built payload and
 * DelayedQbftBlockCreator will fail. This is expected — the round-0 timer expires,
 * round 1 starts, and the round-1 proposer (who started building at round-0 start)
 * succeeds. Block 1 is produced at round 1 instead of round 0.
 */
class QbftBlockCreatorFactory(
  private val manager: ExecutionLayerManager,
  private val proposerSelector: ProposerSelector,
  private val validatorProvider: ValidatorProvider,
  private val beaconChain: BeaconChain,
) : QbftBlockCreatorFactory {
  private val log: Logger = LogManager.getLogger(this.javaClass)

  override fun create(round: Int): BesuQbftBlockCreator {
    val blockNumber = beaconChain.getLatestBeaconState().beaconBlockHeader.number + 1u
    log.debug("Creating block creator: clBlockNumber={}, round={}", blockNumber, round)
    return DelayedQbftBlockCreator(
      manager = manager,
      proposerSelector = proposerSelector,
      validatorProvider = validatorProvider,
      beaconChain = beaconChain,
      round = round,
    )
  }
}
