/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.test.cluster

import maru.app.MaruApp
import maru.app.MaruAppFactory
import maru.config.MaruConfig
import maru.consensus.ForksSchedule
import maru.crypto.PrivateKeyGenerator

fun createMaru(
  elNode: ElNode? = null,
  config: MaruConfig,
  bootnodes: List<String> = emptyList(),
  staticpeers: List<String> = emptyList(),
  nodeKeyData: PrivateKeyGenerator.KeyData,
  nodeRole: NodeRole,
  forkSchedule: ForksSchedule,
): MaruApp {
  initPersistence(config.persistence, nodeKeyData)
  var effectiveConfig = config
  effectiveConfig =
    setQbftConfigIfSequencer(effectiveConfig, isSequencer = nodeRole == NodeRole.Sequencer, nodeKeyData)
  effectiveConfig = setP2pConfig(effectiveConfig, bootnodes, staticpeers)
  effectiveConfig = setValidatorConfig(effectiveConfig, elNode)

  return MaruAppFactory().create(
    config = effectiveConfig,
    beaconGenesisConfig = forkSchedule,
  )
}
