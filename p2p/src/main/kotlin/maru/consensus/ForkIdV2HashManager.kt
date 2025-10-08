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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.toKotlinInstant
import maru.crypto.Hashing
import maru.crypto.Keccak256Hasher
import maru.database.BeaconChain
import maru.serialization.ForkSpecSerializer
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

class ForkIdV2HashManager(
  private val clock: Clock,
  private val genesisForkIdDigest: ByteArray,
  private val forkIdDigester: (ForkIdV2) -> ByteArray,
  private val forkSpecDigester: (ForkSpec) -> ByteArray,
  private val forks: List<ForkSpec>,
  private val peeringForkMismatchLeewayTime: Duration = 5.minutes,
) : ForkIdHashManagerV2 {
  init {
    require(forks.isNotEmpty()) { "empty forks list" }
  }

  companion object {
    fun create(
      chainId: UInt,
      beaconChain: BeaconChain,
      forks: List<ForkSpec>,
      peeringForkMismatchLeewayTime: Duration = 5.minutes,
      clock: Clock,
    ): ForkIdHashManagerV2 {
      val forkIdDigester = ForkIdV2Digester(hasher = Hashing::keccak)
      val genesisBeaconBlockHash =
        beaconChain
          .getBeaconState(0UL)!!
          .beaconBlockHeader
          .hash
      return ForkIdV2HashManager(
        clock = clock,
        genesisForkIdDigest =
          genesisForkIdDigest(
            genesisBeaconBlockHash = genesisBeaconBlockHash,
            chainId = chainId,
            hasher = Keccak256Hasher,
          ),
        forkIdDigester = forkIdDigester::hash,
        forkSpecDigester = ForkSpecSerializer::serialize,
        peeringForkMismatchLeewayTime = peeringForkMismatchLeewayTime,
        forks = forks,
      )
    }
  }

  private data class ForkInfo(
    val forkSpec: ForkSpec,
    val forkIdDigest: ByteArray,
  )

  @OptIn(ExperimentalTime::class)
  private fun ForkInfo.isWithinLeeway(): Boolean {
    val forkTime = Instant.fromEpochSeconds(forkSpec.timestampSeconds.toLong())
    val currentTime = clock.instant().toKotlinInstant()
    val currentTimeMinusLeeway = currentTime.minus(peeringForkMismatchLeewayTime)
    val currentTimePlusLeeway = currentTime.plus(peeringForkMismatchLeewayTime)
    return forkTime in currentTimeMinusLeeway..currentTimePlusLeeway
  }

  private val log: Logger = LogManager.getLogger(this.javaClass)

  /**
   *  List of [ForkInfo] sorted by their [ForkSpec.timestampSeconds] descending order (newest first).
   */
  private val forksInfo: List<ForkInfo> = forkSpecsDigests(forks, forkSpecDigester)

  private fun forkSpecsDigests(
    forks: List<ForkSpec>,
    forkSpecDigester: (ForkSpec) -> ByteArray,
  ): List<ForkInfo> {
    var prevForkDigest = genesisForkIdDigest
    return forks
      .sortedBy { it.timestampSeconds }
      .map { forkSpec ->
        val forkIdDigest = forkIdDigester(ForkIdV2(prevForkDigest, forkSpecDigester(forkSpec)))
        prevForkDigest = forkIdDigest
        ForkInfo(forkSpec, forkIdDigest)
      }.reversed()
  }

  private fun currentForkIndex(): Int {
    val currentTimestamp = clock.instant().epochSecond.toULong()
    return forksInfo.indexOfFirst { currentTimestamp >= it.forkSpec.timestampSeconds }
  }

  private fun currentFork(): ForkInfo = forksInfo[currentForkIndex()]

  private fun nextFork(): ForkInfo? = forksInfo.getOrNull(index = currentForkIndex() + 1)

  private fun prevFork(): ForkInfo? = forksInfo.getOrNull(index = currentForkIndex() - 1)

  override fun currentForkHash(): ByteArray = currentFork().forkIdDigest

  override fun highestForkHash(): ByteArray = forksInfo.first().forkIdDigest

  override fun isValidForkIdForPeering(otherForkIdHash: ByteArray): Boolean {
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
