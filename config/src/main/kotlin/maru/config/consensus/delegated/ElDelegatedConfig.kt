/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.config.consensus.delegated

import maru.consensus.ConsensusConfig

data class ElDelegatedConfig(
  val postTtdConfig: ConsensusConfig,
  val terminalTotalDifficulty: ULong,
) : ConsensusConfig
