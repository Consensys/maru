/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.database

import maru.core.BeaconState
import maru.core.SealedBeaconBlock

interface BeaconChain : AutoCloseable {
  fun isInitialized(): Boolean

  fun getLatestBeaconState(): BeaconState

  fun getBeaconState(beaconBlockRoot: ByteArray): BeaconState?

  fun getBeaconState(beaconBlockNumber: ULong): BeaconState?

  fun getSealedBeaconBlock(beaconBlockRoot: ByteArray): SealedBeaconBlock?

  fun getSealedBeaconBlock(beaconBlockNumber: ULong): SealedBeaconBlock?

  fun getSealedBlocks(
    startBlockNumber: ULong,
    count: ULong,
  ): List<SealedBeaconBlock> {
    if (count == 0uL) return emptyList()
    val latestBlockNumber = getLatestBeaconState().latestBeaconBlockHeader.number

    // If start block is beyond latest available, return empty
    if (startBlockNumber > latestBlockNumber) return emptyList()

    // Cap the count to not exceed available blocks
    val effectiveCount = minOf(count, latestBlockNumber - startBlockNumber + 1uL)

    return (startBlockNumber until startBlockNumber + effectiveCount)
      .asSequence()
      .map { blockNumber ->
        getSealedBeaconBlock(blockNumber)
          ?: throw IllegalStateException(
            "Missing block at number $blockNumber, expected to be present in the database.",
          )
      }.toList()
  }

  fun newUpdater(): Updater

  interface Updater : AutoCloseable {
    fun putBeaconState(beaconState: BeaconState): Updater

    fun putSealedBeaconBlock(sealedBeaconBlock: SealedBeaconBlock): Updater

    fun commit(): Unit

    fun rollback(): Unit
  }
}
