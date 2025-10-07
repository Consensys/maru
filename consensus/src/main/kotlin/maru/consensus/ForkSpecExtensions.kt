/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.consensus

import maru.config.consensus.ElFork
import maru.config.consensus.qbft.DifficultyAwareQbftConfig
import maru.config.consensus.qbft.QbftConsensusConfig

/**
 * Extracts the EL fork from a ForkSpec based on its consensus configuration.
 */
fun ForkSpec.extractElFork(): ElFork =
  when (configuration) {
    is QbftConsensusConfig -> (configuration as QbftConsensusConfig).elFork
    is DifficultyAwareQbftConfig -> (configuration as DifficultyAwareQbftConfig).postTtdConfig.elFork
    else -> throw IllegalStateException(
      "Current fork isn't QBFT nor DifficultyAwareQbft, this case is not supported yet! forkSpec=$this",
    )
  }
