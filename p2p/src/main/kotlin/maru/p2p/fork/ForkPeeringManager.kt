/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.p2p.fork

import java.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.toKotlinInstant
import maru.consensus.ForkSpec
import maru.database.BeaconChain

data class ForkInfo(
  val forkSpec: ForkSpec,
  val forkIdDigest: ByteArray,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as ForkInfo

    if (forkSpec != other.forkSpec) return false
    if (!forkIdDigest.contentEquals(other.forkIdDigest)) return false

    return true
  }

  override fun hashCode(): Int {
    var result = forkSpec.hashCode()
    result = 31 * result + forkIdDigest.contentHashCode()
    return result
  }
}

interface ForkPeeringManager {
  /**
   *  Returns the highest fork hash known to this node,
   *  based on its local genesis file configuration.
   */
  fun highestForkHash(): ByteArray

  /**
   *  Returns the current fork hash, based on current time.
   */
  fun currentForkHash(): ByteArray

  fun isValidForPeering(otherForkIdHash: ByteArray): Boolean
}

class LenientForkPeeringManager internal constructor(
  private val clock: Clock,
  private val forks: List<ForkInfo>,
  private val peeringForkMismatchLeewayTime: Duration,
) : ForkPeeringManager {
  init {
    require(forks.isNotEmpty()) { "empty forks list" }
  }

  companion object {
    fun create(
      chainId: UInt,
      beaconChain: BeaconChain,
      forks: List<ForkSpec>,
      peeringForkMismatchLeewayTime: Duration,
      clock: Clock,
    ): LenientForkPeeringManager {
      val digestsCalculator = RollingForwardForkIdDigestCalculator(chainId, beaconChain)
      return LenientForkPeeringManager(
        clock = clock,
        peeringForkMismatchLeewayTime = peeringForkMismatchLeewayTime,
        forks = digestsCalculator.calculateForkDigests(forks),
      )
    }
  }

  @OptIn(ExperimentalTime::class)
  private fun ForkInfo.isWithinLeeway(): Boolean {
    val forkTime = Instant.fromEpochSeconds(forkSpec.timestampSeconds.toLong())
    val currentTime = clock.instant().toKotlinInstant()
    val currentTimeMinusLeeway = currentTime.minus(peeringForkMismatchLeewayTime)
    val currentTimePlusLeeway = currentTime.plus(peeringForkMismatchLeewayTime)
    return forkTime in currentTimeMinusLeeway..currentTimePlusLeeway
  }

  /**
   *  List of [ForkInfo] sorted by their [ForkSpec.timestampSeconds] descending order (newest first).
   */
  internal val forksInfo: List<ForkInfo> = forks.sortedBy { it.forkSpec.timestampSeconds }.reversed()

  private fun currentForkIndex(): Int {
    val currentTimestamp = clock.instant().epochSecond.toULong()
    return forksInfo.indexOfFirst { currentTimestamp >= it.forkSpec.timestampSeconds }
  }

  internal fun currentFork(): ForkInfo = forksInfo[currentForkIndex()]

  internal fun nextFork(): ForkInfo? = forksInfo.getOrNull(index = currentForkIndex() - 1)

  internal fun prevFork(): ForkInfo? = forksInfo.getOrNull(index = currentForkIndex() + 1)

  override fun currentForkHash(): ByteArray = currentFork().forkIdDigest

  override fun highestForkHash(): ByteArray = forksInfo.first().forkIdDigest

  override fun isValidForPeering(otherForkIdHash: ByteArray): Boolean {
    val currentFork = currentFork()
    if (currentFork.forkIdDigest.contentEquals(otherForkIdHash)) {
      // most probable case
      return true
    }
    val otherPeerFork = forksInfo.firstOrNull { it.forkIdDigest.contentEquals(otherForkIdHash) }
    if (otherPeerFork == null) {
      // it means peer for does not match any of our forks,
      // possible cases:
      // A - peer has outdated genesis file, missing latest fork configs
      // B - peer has a different genesis file, a real network fork
      return false
    }
    // to handle cases around network switching to the next fork
    // also fork may mismatch due to network latency, clock out of sync
    if (otherPeerFork == nextFork() && otherPeerFork.isWithinLeeway()) {
      // A - peer has already switched to the next fork
      return true
    }
    val previousFork = prevFork()
    if (otherPeerFork == previousFork && currentFork().isWithinLeeway()) {
      // B - we already switched to the next fork, peer still on the previous fork
      // but allow to connect because we just switched recently and is within leeway
      return true
    }
    return false
  }
}
