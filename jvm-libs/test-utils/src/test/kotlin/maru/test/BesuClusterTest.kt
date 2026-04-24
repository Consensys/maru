/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.test

import java.net.ConnectException
import java.util.Optional
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import maru.test.cluster.BesuCluster
import maru.test.extensions.latestBlockNumber
import maru.test.extensions.nodeHeads
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.fail
import org.awaitility.kotlin.await
import org.hyperledger.besu.tests.acceptance.dsl.node.BesuNode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import testutils.besu.BesuFactory

class BesuClusterTest {
  fun createBesu(
    label: String,
    miningEnabled: Boolean = false,
    jsonRpcPort: Int? = null,
  ): BesuNode =
    BesuFactory.buildTestBesuQbftCluster(
      nodeName = label,
      miningEnabled = miningEnabled,
      jsonRpcPort = Optional.ofNullable(jsonRpcPort),
    )

  private lateinit var cluster: BesuCluster

  @AfterEach
  fun afterEach() {
    if (::cluster.isInitialized) {
      cluster.stop()
    }
  }

  @Test
  fun `should allow to add nodes to existing cluster and sync`() {
    cluster =
      BesuCluster()
        .apply {
          addNode(createBesu("besu-0", miningEnabled = true))
          addNode(createBesu("besu-1", miningEnabled = true))
          addNode(createBesu("besu-2", miningEnabled = true))
          start(false)
        }

    await
      .atMost(30.seconds.toJavaDuration())
      .untilAsserted {
        cluster.assertNodesAreSyncedUpTo(3UL)
      }

    val node2 = cluster.nodes["besu-2"]!!
    cluster.stopNode("besu-2")
    assertThrows<ConnectException> { node2.latestBlockNumber() }

    val newBesu = createBesu("besu-new-0", miningEnabled = false)
    cluster.addNodeAndStart(newBesu, awaitPeerDiscovery = false)
    await
      .atMost(120.seconds.toJavaDuration())
      .untilAsserted {
        assertThat(newBesu.latestBlockNumber()).isGreaterThanOrEqualTo(5UL)
      }
  }

  @Test
  fun `should allow to start nodes 1 by 1 and sync`() {
    cluster =
      BesuCluster()
        .apply {
          addNode(createBesu("besu-0", miningEnabled = true))
          addNode(createBesu("besu-1", miningEnabled = true))
          addNode(createBesu("besu-2", miningEnabled = true))
          start(false)
        }

    await
      .atMost(120.seconds.toJavaDuration())
      .untilAsserted {
        cluster.assertNodesAreSyncedUpTo(3UL)
      }

    val newBesu = createBesu("besu-extra", miningEnabled = false)
    cluster.addNodeAndStart(newBesu)
    await
      .atMost(120.seconds.toJavaDuration())
      .untilAsserted {
        assertThat(newBesu.latestBlockNumber()).isGreaterThanOrEqualTo(5UL)
      }
  }

  @Test
  fun `should remove,stop and add back nodes to the cluster`() {
    cluster =
      BesuCluster().apply {
        addNode(createBesu("sequencer", miningEnabled = true))
        addNode(createBesu("follower-1", miningEnabled = true))
        start(false)
      }

    await
      .atMost(120.seconds.toJavaDuration())
      .untilAsserted {
        cluster.assertNodesAreSyncedUpTo(3UL)
      }

    val sequencer = cluster.nodes["sequencer"]!!
    val lastMinedBlock = sequencer.latestBlockNumber()
    cluster.stopNode("sequencer")

    assertThrows<ConnectException> { sequencer.latestBlockNumber() }
    cluster.addNodeAndStart(sequencer)

    await
      .atMost(120.seconds.toJavaDuration())
      .untilAsserted {
        assertThat(cluster.nodes["follower-1"]!!.latestBlockNumber()).isGreaterThanOrEqualTo(lastMinedBlock + 3UL)
      }
  }

  fun BesuCluster.assertNodesAreSyncedUpTo(targetBlockNumber: ULong) {
    val nodesHeadBlockNumbers = this.nodeHeads()
    val inSync = nodesHeadBlockNumbers.values.all { besuHead -> besuHead >= targetBlockNumber }

    if (!inSync) {
      fail<Unit>("Nodes did not sync to block $targetBlockNumber nodes heads: $nodesHeadBlockNumbers")
    }
  }
}
