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

interface ForkIdHashProvider {
  fun currentForkIdHash(): ByteArray
}

class ForkIdHashProviderImpl(
  private val chainId: UInt,
  private val beaconChain: BeaconChain,
  private val forksSchedule: ForksSchedule,
  private val forkIdHasher: ForkIdHasher,
  private val clock: Clock = Clock.systemUTC(),
) : ForkIdHashProvider {
  private val log: Logger = LogManager.getLogger(this.javaClass)

  init {
    log.info("current fork id hash: ${currentForkIdHash().toHexString()}")
    log.info("all fork id hashes: ${allForkIdHashes().map { it.toHexString()}}")
  }

  override fun currentForkIdHash(): ByteArray {
    val forkId =
      ForkId(
        chainId = chainId,
        forkSpec =
          forksSchedule.getForkByTimestamp(
            timestamp = clock.instant().epochSecond.toULong(),
          ),
        genesisRootHash =
          beaconChain.getBeaconState(0u)?.beaconBlockHeader?.hash
            ?: throw IllegalStateException("Genesis state not found"),
      )
    return forkIdHasher.hash(forkId)
  }

  private fun allForkIdHashes(): List<ByteArray> {
    val forkIdHashes = mutableListOf<ByteArray>()
    forksSchedule.forks.forEach { fork ->
      val forkId =
        ForkId(
          chainId = chainId,
          forkSpec =
            forksSchedule.getForkByTimestamp(timestamp = fork.timestampSeconds),
          genesisRootHash =
            beaconChain.getBeaconState(0u)?.beaconBlockHeader?.hash
              ?: throw IllegalStateException("Genesis state not found"),
        )
      forkIdHashes.add(forkIdHasher.hash(forkId = forkId))
    }
    return forkIdHashes
  }
}
