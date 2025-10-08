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
import maru.core.ext.DataGenerators
import maru.database.BeaconChain

object ForkIdManagerFactory {
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
    peeringForkMismatchLeewayTime: Duration = 5.minutes,
    clock: Clock = Clock.systemUTC(),
  ): ForkIdHashManager =
    ForkIdV2HashManager.create(
      chainId = chainId,
      beaconChain = beaconChain,
      forks = forks,
      peeringForkMismatchLeewayTime = peeringForkMismatchLeewayTime,
      clock = clock,
    )
}
