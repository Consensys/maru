/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.test

import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.times
import kotlin.time.toJavaDuration
import linea.kotlin.toULong
import maru.consensus.ChainFork
import maru.consensus.ClFork
import maru.consensus.ElFork
import maru.test.cluster.MaruCluster
import maru.test.cluster.NodeRole
import maru.test.cluster.configureLoggers
import maru.test.extensions.assertNodesAreSyncedUpTo
import maru.test.extensions.headBeaconBlockNumber
import maru.test.extensions.latestBlock
import org.apache.logging.log4j.Level
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MaruClusterTest {
  @BeforeEach
  fun beforeEach() {
    configureLoggers(
      rootLevel = Level.WARN,
      logLevels =
        listOf(
          "maru" to Level.INFO,
          "maru.clients" to Level.DEBUG,
        ),
    )
  }

  @Test
  fun `should allow to retrieve nodes by label`() {
    val cluster =
      MaruCluster()
        .addNode(NodeRole.Follower)
        .addNode(NodeRole.Follower) {
          it.withLabel("follower-1")
        }.addNode(NodeRole.Follower) {
          it.withLabel("follower-2")
        }.addNode(NodeRole.Sequencer)
        .addNode("follower-special")
        .start()

    assertThat(cluster.node("follower").nodeRole).isEqualTo(NodeRole.Follower)
    assertThat(cluster.node("follower-1").nodeRole).isEqualTo(NodeRole.Follower)
    assertThat(cluster.node("follower-2").nodeRole).isEqualTo(NodeRole.Follower)
    assertThat(cluster.node("follower-special").nodeRole).isEqualTo(NodeRole.Follower)
    assertThat(cluster.node("follower-special").nodeRole).isEqualTo(NodeRole.Follower)
    assertThat(cluster.node("sequencer").nodeRole).isEqualTo(NodeRole.Sequencer)

    cluster.stop()
  }

  @Test
  fun `should create network starting at prague`() {
    val cluster =
      MaruCluster(
        chainForks =
          mapOf(
            // Genesis at Prague
            Instant.fromEpochSeconds(0L) to
              ChainFork(
                ClFork.QBFT_PHASE0,
                ElFork.Prague,
              ),
          ),
      ).addNode(NodeRole.Sequencer) { nodeBuilder ->
        nodeBuilder.withLabel("sequencer")
      }.start()

    await()
      .pollInterval(1.seconds.toJavaDuration())
      .atMost(120.seconds.toJavaDuration())
      .untilAsserted {
        cluster.assertNodesAreSyncedUpTo(targetBlockNumber = 5UL)
      }

    cluster.stop()
  }

  @Test
  fun `should create network starting with all forks and switch post ttd`() {
    val now = Clock.System.now()
    val terminalTotalDifficulty = 20UL
    val forkTimeGap = 10.seconds
    val cluster =
      MaruCluster(
        terminalTotalDifficulty = 20UL,
        chainForks =
          mapOf(
            Instant.fromEpochSeconds(0) to
              ChainFork(
                ClFork.QBFT_PHASE0,
                ElFork.Paris,
              ),
            now.plus(30.seconds) to
              ChainFork(
                ClFork.QBFT_PHASE0,
                ElFork.Shanghai,
              ),
            now.plus(30.seconds + forkTimeGap) to
              ChainFork(
                ClFork.QBFT_PHASE0,
                ElFork.Cancun,
              ),
            now.plus(30.seconds + 2 * forkTimeGap) to
              ChainFork(
                ClFork.QBFT_PHASE0,
                ElFork.Prague,
              ),
          ),
      ).addNode("sequencer")
        .start()

    await()
      .atMost(120.seconds.toJavaDuration())
      .untilAsserted {
        val headBlock =
          cluster
            .besuNode("sequencer")
            .latestBlock()
        assertThat(headBlock.totalDifficulty.toULong())
          .withFailMessage { "Sequencer did not past ttd=$terminalTotalDifficulty" }
          .isGreaterThanOrEqualTo(terminalTotalDifficulty)
      }
  }

  @Test
  fun `should instantiate multiple nodes in the cluster with static peering and sync`() {
    val cluster =
      MaruCluster()
        .addNode("sequencer")
        .addNode("follower-internal-0") { nodeBuilder ->
          nodeBuilder
            .staticPeers(listOf("sequencer"))
        }.addNode("follower-internal-1") { nodeBuilder ->
          nodeBuilder
            .staticPeers(listOf("sequencer"))
        }.addNode("follower-internal-2") { nodeBuilder ->
          nodeBuilder
            .staticPeers(listOf("sequencer"))
        }.start()

    await()
      .pollInterval(1.seconds.toJavaDuration())
      .atMost(120.seconds.toJavaDuration())
      .untilAsserted {
        cluster.assertNodesAreSyncedUpTo(targetBlockNumber = 5UL)
      }
    cluster.stop()
  }

  @Test
  fun `should instantiate multiple nodes in the cluster with discovery`() {
    val cluster =
      MaruCluster()
        .addNode("bootnode-0")
        .addNode("bootnode-1")
        .addNode("sequencer")
        .addNode("follower-internal-0")
        .addNode("follower-internal-1")
        .addNode("follower-internal-2")
        .start()
    val followers = cluster.nodes(NodeRole.Follower)

    await()
      .pollInterval(1.seconds.toJavaDuration())
      .atMost(120.seconds.toJavaDuration())
      .untilAsserted {
        followers
          .forEach { assertThat(it.maru.p2pNetwork.peerCount).isGreaterThanOrEqualTo(cluster.nodeCount() - 1) }
      }

    await()
      .pollInterval(1.seconds.toJavaDuration())
      .atMost(120.seconds.toJavaDuration())
      .untilAsserted {
        cluster.assertNodesAreSyncedUpTo(targetBlockNumber = 5UL)
      }

    cluster.stop()
  }

  @Test
  fun `should allow to add nodes after cluster is has started`() {
    val cluster =
      MaruCluster()
        .addNode("bootnode-0")
        .addNode("sequencer")
        .addNode("follower-internal-0")
        .addNode("follower-internal-1")
        .addNode("follower-internal-2")
        .start()

    await()
      .pollInterval(1.seconds.toJavaDuration())
      .atMost(90.seconds.toJavaDuration())
      .untilAsserted { cluster.assertNodesAreSyncedUpTo(targetBlockNumber = 2UL) }

    // add new node and wait for sync
    cluster.addNewExtraNodeAndStart("follower-new-node")
    await()
      .pollInterval(1.seconds.toJavaDuration())
      // besu peering takes time sometimes, necessary for back sync
      .atMost(120.seconds.toJavaDuration())
      .untilAsserted {
        cluster.node("follower-new-node").let { newNode ->
          assertThat(newNode.maru.p2pNetwork.peerCount).isGreaterThanOrEqualTo(cluster.nodeCount() - 1)
          assertThat(newNode.maru.headBeaconBlockNumber()).isGreaterThanOrEqualTo(5UL)
        }
      }

    cluster.stop()
  }
}
