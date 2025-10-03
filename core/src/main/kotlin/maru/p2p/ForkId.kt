/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.p2p

import java.util.concurrent.atomic.AtomicReference
import maru.consensus.ForkSpec
import maru.consensus.ForksSchedule
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
  beaconChain: BeaconChain,
  private val forksSchedule: ForksSchedule,
  private val forkIdHasher: ForkIdHasher,
  private val initialTimestamp: ULong,
) : ForkIdHashManager {
  private val log: Logger = LogManager.getLogger(this.javaClass)

  private val genesisRootHash: ByteArray = beaconChain.getBeaconState(0u)!!.beaconBlockHeader.hash

  private val currentForkIdHash: AtomicReference<ByteArray> =
    run {
      val newForkSpec = forksSchedule.getForkByTimestamp(initialTimestamp)
      AtomicReference(getForkIdHashForForkSpec(newForkSpec))
    }

  override fun currentHash(): ByteArray = currentForkIdHash.get()

  private fun getForkIdHashForForkSpec(forkSpec: ForkSpec): ByteArray {
    val forkId =
      ForkId(
        chainId = chainId,
        forkSpec = forkSpec,
        genesisRootHash = genesisRootHash,
      )
    return forkIdHasher.hash(forkId)
  }

  override fun check(otherForkIdHash: ByteArray): Boolean = otherForkIdHash.contentEquals(currentForkIdHash.get())

  override fun update(newForkSpec: ForkSpec) {
    log.debug(
      "Updating fork id hash to {} for fork spec={}",
      getForkIdHashForForkSpec(newForkSpec).toHexString(),
      newForkSpec,
    )
    currentForkIdHash.set(getForkIdHashForForkSpec(newForkSpec))
  }
}
