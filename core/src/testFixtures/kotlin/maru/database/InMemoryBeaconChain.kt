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

class InMemoryBeaconChain(
  initialBeaconState: BeaconState,
  initialBeaconBlock: SealedBeaconBlock? = null,
) : BeaconChain {
  private val beaconStateByBlockRoot = mutableListOf<Pair<ByteArray, BeaconState>>()
  private val beaconStateByBlockNumber = mutableMapOf<ULong, BeaconState>()
  private val sealedBeaconBlockByBlockRoot = mutableListOf<Pair<ByteArray, SealedBeaconBlock>>()
  private val sealedBeaconBlockByBlockNumber = mutableMapOf<ULong, SealedBeaconBlock>()

  private var latestBeaconState: BeaconState = initialBeaconState

  init {
    val updater = newUpdater()
    updater.putBeaconState(initialBeaconState)
    initialBeaconBlock?.let { updater.putSealedBeaconBlock(initialBeaconBlock) }
    updater.commit()
  }

  override fun isInitialized(): Boolean = true

  override fun getLatestBeaconState(): BeaconState = latestBeaconState

  override fun getBeaconState(beaconBlockRoot: ByteArray): BeaconState? =
    beaconStateByBlockRoot.firstOrNull { it.first.contentEquals(beaconBlockRoot) }?.second

  override fun getBeaconState(beaconBlockNumber: ULong): BeaconState? = beaconStateByBlockNumber[beaconBlockNumber]

  override fun getSealedBeaconBlock(beaconBlockRoot: ByteArray): SealedBeaconBlock? =
    sealedBeaconBlockByBlockRoot.firstOrNull { it.first.contentEquals(beaconBlockRoot) }?.second

  override fun getSealedBeaconBlock(beaconBlockNumber: ULong): SealedBeaconBlock? =
    sealedBeaconBlockByBlockNumber[beaconBlockNumber]

  override fun newUpdater(): BeaconChain.Updater = InMemoryUpdater(this)

  override fun close() {
    // No-op for in-memory beacon chain
  }

  private class InMemoryUpdater(
    private val beaconChain: InMemoryBeaconChain,
  ) : BeaconChain.Updater {
    private val beaconStateByBlockRoot = mutableListOf<Pair<ByteArray, BeaconState>>()
    private val beaconStateByBlockNumber = mutableMapOf<ULong, BeaconState>()
    private val sealedBeaconBlockByBlockRoot = mutableListOf<Pair<ByteArray, SealedBeaconBlock>>()
    private val sealedBeaconBlockByBlockNumber = mutableMapOf<ULong, SealedBeaconBlock>()

    private var newBeaconState: BeaconState? = null

    override fun putBeaconState(beaconState: BeaconState): BeaconChain.Updater {
      beaconStateByBlockRoot.add(beaconState.latestBeaconBlockHeader.hash to beaconState)
      beaconStateByBlockNumber[beaconState.latestBeaconBlockHeader.number] = beaconState
      newBeaconState = beaconState
      return this
    }

    override fun putSealedBeaconBlock(sealedBeaconBlock: SealedBeaconBlock): BeaconChain.Updater {
      sealedBeaconBlockByBlockRoot.add(sealedBeaconBlock.beaconBlock.beaconBlockHeader.hash to sealedBeaconBlock)
      sealedBeaconBlockByBlockNumber[sealedBeaconBlock.beaconBlock.beaconBlockHeader.number] = sealedBeaconBlock
      return this
    }

    override fun commit() {
      beaconChain.beaconStateByBlockRoot.addAll(beaconStateByBlockRoot)
      beaconChain.beaconStateByBlockNumber.putAll(beaconStateByBlockNumber)
      beaconChain.sealedBeaconBlockByBlockRoot.addAll(sealedBeaconBlockByBlockRoot)
      beaconChain.sealedBeaconBlockByBlockNumber.putAll(sealedBeaconBlockByBlockNumber)
      if (newBeaconState != null) {
        beaconChain.latestBeaconState = newBeaconState!!
      }
    }

    override fun rollback() {
      beaconStateByBlockRoot.clear()
      sealedBeaconBlockByBlockRoot.clear()
      sealedBeaconBlockByBlockNumber.clear()
      newBeaconState = null
    }

    override fun close() {
      // No-op for in-memory updater
    }
  }
}
