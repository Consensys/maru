/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.consensus

import java.time.Clock
import maru.core.Hasher
import maru.database.BeaconChain
import maru.serialization.Serializer
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

data class ForkId(
  val chainId: UInt,
  val forkSpec: ForkSpec,
  val genesisRootHash: ByteArray,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as ForkId

    if (chainId != other.chainId) return false
    if (forkSpec != other.forkSpec) return false
    if (!genesisRootHash.contentEquals(other.genesisRootHash)) return false

    return true
  }

  override fun hashCode(): Int {
    var result = chainId.hashCode()
    result = 31 * result + forkSpec.hashCode()
    result = 31 * result + genesisRootHash.contentHashCode()
    return result
  }
}

class ForkIdHasher(
  val forkIdSerializer: Serializer<ForkId>,
  val hasher: Hasher,
) {
  fun hash(forkId: ForkId): ByteArray = hasher.hash(forkIdSerializer.serialize(forkId)).takeLast(4).toByteArray()
}

interface ForkIdHashManager {
  fun currentHash(): ByteArray

  fun check(otherForkIdHash: ByteArray): Boolean

  fun update(newForkSpec: ForkSpec)
}

class ForkIdHashManagerImpl(
  private val chainId: UInt,
  private val beaconChain: BeaconChain,
  private val forksSchedule: ForksSchedule,
  private val forkIdHasher: ForkIdHasher,
  private val clock: Clock = Clock.systemUTC(),
  private val allowedTimeWindowSeconds: ULong = 5U,
  private val protocolTransitionPollingInterval: ULong = 1U,
) : ForkIdHashManager {
  private val log: Logger = LogManager.getLogger(this.javaClass)

  private var previousForkIdHash: ByteArray?
  private var currentForkIdHash: ByteArray
  private var nextForkIdHash: ByteArray?
  private var currentForkTimestamp: ULong
  private var nextForkTimestamp: ULong
  private var currentBlockTime: UInt
  private var nextBlockTime: UInt

  init {
    val timestamp = clock.instant().epochSecond.toULong()
    val previousForkByTimestamp = forksSchedule.getPreviousForkByTimestamp(timestamp)
    val currentForkByTimestamp = forksSchedule.getForkByTimestamp(timestamp)
    val nextForkByTimestamp = forksSchedule.getNextForkByTimestamp(timestamp)

    currentForkTimestamp = currentForkByTimestamp.timestampSeconds
    nextForkTimestamp = nextForkByTimestamp?.timestampSeconds ?: ULong.MAX_VALUE

    currentBlockTime = forksSchedule.getForkByTimestamp(timestamp).blockTimeSeconds
    nextBlockTime = nextForkByTimestamp?.blockTimeSeconds ?: 0U

    previousForkIdHash = previousForkByTimestamp?.let { getForkIdHashForForkSpec(it) }
    currentForkIdHash = getForkIdHashForForkSpec(currentForkByTimestamp)
    nextForkIdHash = nextForkByTimestamp?.let { getForkIdHashForForkSpec(it) }
  }

  private var _genesisRootHash: ByteArray? = null
  val genesisRootHash: ByteArray
    get() {
      if (_genesisRootHash == null) {
        _genesisRootHash = beaconChain.getBeaconState(0u)?.beaconBlockHeader?.hash
          ?: throw IllegalStateException("Genesis state not found")
      }
      return _genesisRootHash!!
    }

  override fun currentHash(): ByteArray = currentForkIdHash

  private fun getForkIdHashForForkSpec(forkSpec: ForkSpec): ByteArray {
    val forkId =
      ForkId(
        chainId = chainId,
        forkSpec =
        forkSpec,
        genesisRootHash = genesisRootHash,
      )
    return forkIdHasher.hash(forkId)
  }

  override fun check(otherForkIdHash: ByteArray): Boolean {
    if (otherForkIdHash.contentEquals(currentForkIdHash)) return true
    val currentTime = clock.instant().epochSecond.toULong()
    // The allowedTimeWindowSeconds allows for time drift, network latency, etc.
    // The poll interval should be the maximum time between the two nodes switching forks (without time drift).
    // Current block time is subtracted from the current fork timestamp, because that is what we do in the fork update logic.
    // 1U for rounding errors.
    if (previousForkIdHash != null &&
      currentTime <=
      currentForkTimestamp - currentBlockTime + protocolTransitionPollingInterval + allowedTimeWindowSeconds + 1U &&
      otherForkIdHash.contentEquals(previousForkIdHash)
    ) { // this is the case where we have already switched fork
      return true
    }
    if (nextForkIdHash != null &&
      currentTime + protocolTransitionPollingInterval + allowedTimeWindowSeconds + 1U >=
      nextForkTimestamp - currentBlockTime &&
      otherForkIdHash.contentEquals(nextForkIdHash)
    ) { // this is the case where we haven't switched fork yet
      return true
    }
    return false
  }

  override fun update(newForkSpec: ForkSpec) {
    val newForkIdHash = getForkIdHashForForkSpec(newForkSpec)
    if (getForkIdHashForForkSpec(newForkSpec).contentEquals(currentForkIdHash)) {
      // currentForkIdHash has already been set during initialization.
      return
    }
    require(nextForkIdHash != null && newForkIdHash.contentEquals(nextForkIdHash)) {
      "Inconsistent fork id hashes: $nextForkIdHash vs $newForkIdHash"
    }

    val nextFork = forksSchedule.getNextForkByTimestamp(clock.instant().epochSecond.toULong())
    previousForkIdHash = currentForkIdHash
    currentForkIdHash = newForkIdHash
    nextForkIdHash = nextFork?.let { getForkIdHashForForkSpec(it) }

    currentForkTimestamp = newForkSpec.timestampSeconds
    nextForkTimestamp = nextFork?.timestampSeconds ?: ULong.MAX_VALUE

    currentBlockTime = newForkSpec.blockTimeSeconds
    nextBlockTime = nextFork?.blockTimeSeconds ?: 0U
  }
}
