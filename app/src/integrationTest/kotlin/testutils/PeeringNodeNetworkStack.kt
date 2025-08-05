/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package testutils

import java.nio.file.Files
import java.nio.file.Path
import maru.app.MaruApp
import testutils.besu.BesuFactory
import testutils.besu.startWithRetry
import org.hyperledger.besu.tests.acceptance.dsl.node.BesuNode
import org.hyperledger.besu.tests.acceptance.dsl.node.cluster.Cluster

/**
 * NetworkParticipantStack for multi-node scenarios with proper peering support.
 * This class separates Besu node creation from Maru app creation to allow for
 * proper coordination between multiple nodes.
 */
class PeeringNodeNetworkStack(
  besuBuilder: (() -> BesuNode)? = null,
) {
  val besuNode = besuBuilder?.invoke() ?: BesuFactory.buildTestBesu()
  val tmpDir: Path =
    Files.createTempDirectory("maru-app").also {
      it.toFile().deleteOnExit()
    }

  val maruApp: MaruApp
    get() = _maruApp
  private lateinit var _maruApp: MaruApp

  fun setMaruApp(maruApp: MaruApp) {
    _maruApp = maruApp
  }

  fun stop() {
    _maruApp.stop()
    besuNode.stop()
  }

  val p2pPort: UInt
    get() = maruApp.p2pPort()

  companion object {
    /**
     * Starts multiple Besu nodes together for proper peering.
     * This only starts the Besu nodes - Maru apps need to be created/started separately.
     */
    fun startBesuNodes(
      cluster: Cluster,
      vararg participantStacks: PeeringNodeNetworkStack,
    ) {
      val allNodes = participantStacks.map { it.besuNode }.toTypedArray()
      cluster.startWithRetry(*allNodes)
    }
  }
}
