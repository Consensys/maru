/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.consensus

import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import maru.core.ext.DataGenerators
import maru.crypto.Hashing
import maru.crypto.Keccak256Hasher
import maru.database.BeaconChain
import maru.serialization.ForkIdSerializer

object ForkIdManagerFactory {
  fun createForkIdHashManagerOld(
    chainId: UInt,
    beaconChain: BeaconChain,
    elFork: ElFork = ElFork.Prague,
    consensusConfig: ConsensusConfig =
      QbftConsensusConfig(
        validatorSet =
          setOf(
            DataGenerators.randomValidator(),
            DataGenerators.randomValidator(),
          ),
        elFork = elFork,
      ),
    forks: List<ForkSpec> = listOf(ForkSpec(0UL, 1u, consensusConfig)),
  ): ForkIdHashManager {
    val forksSchedule = ForksSchedule(chainId = chainId, forks = forks)

    return ForkIdHashManagerImpl(
      chainId = chainId,
      beaconChain = beaconChain,
      forksSchedule = forksSchedule,
      forkIdHasher = ForkIdHasher(ForkIdSerializer, Hashing::shortShaHash),
    )
  }

  @OptIn(ExperimentalTime::class)
  fun createForkIdHashManager(
    chainId: UInt,
    beaconChain: BeaconChain,
    elFork: ElFork = ElFork.Prague,
    consensusConfig: ConsensusConfig =
      QbftConsensusConfig(
        validatorSet =
          setOf(
            DataGenerators.randomValidator(),
            DataGenerators.randomValidator(),
          ),
        elFork = elFork,
      ),
    forks: List<ForkSpec> = listOf(ForkSpec(0UL, 1u, consensusConfig)),
  ): ForkIdHashManager {
    val forksSchedule = ForksSchedule(chainId = chainId, forks = forks)
    val genesisBeaconBlock = beaconChain.getSealedBeaconBlock(0UL)!!.beaconBlock
    val genesisForkIdDigest =
      genesisForkIdDigest(
        genesisBeaconBlockHash = genesisBeaconBlock.beaconBlockHeader.hash,
        chainId = chainId,
        hasher = Keccak256Hasher,
      )
    return ForkIdV2HashManager(
      genesisForkIdDigest = genesisForkIdDigest,
      forkIdDigester = { forkId -> ByteArray(4) },
      forkSpecDigester = { forkSpec -> ByteArray(4) },
      forks = forksSchedule.forks.toList(),
      peeringForkMismatchLeewayTime = 5.minutes,
    )
  }
}
