/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.test.genesis

import kotlin.time.Instant
import maru.consensus.ChainFork
import maru.consensus.ForksSchedule

class GenesisFactory(
  val chainId: UInt,
  val blockTimeSeconds: UInt,
) {
  private val beaconGenesisFactory: MaruGenesisFactory = MaruGenesisFactory()
  private val besuGenesisFactory: BesuGenesisFactory = BesuGenesisFactory()
  private lateinit var forkSchedule: ForksSchedule

  fun initForkSchedule(
    sequencersAddresses: List<ByteArray>,
    terminalTotalDifficulty: ULong? = null,
    chainForks: Map<Instant, ChainFork> = emptyMap(),
  ) {
    forkSchedule =
      beaconGenesisFactory
        .create(
          blockTimeSeconds = blockTimeSeconds,
          chainId = chainId,
          validators = sequencersAddresses,
          terminalTotalDifficulty = terminalTotalDifficulty,
          forks = chainForks,
        )
    besuGenesisFactory.setForkSchedule(forkSchedule)
    println("Initialized fork schedule: $forkSchedule")
  }

  fun besuGenesis(): String {
    if (!this::forkSchedule.isInitialized) {
      throw IllegalStateException("forkSchedule must be initialized before calling creating genesis")
    }

    return besuGenesisFactory.create()
  }

  fun maruForkSchedule(): ForksSchedule {
    if (!this::forkSchedule.isInitialized) {
      throw IllegalStateException("forkSchedule must be initialized before calling creating genesis")
    }
    return forkSchedule
  }
}
