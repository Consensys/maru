/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package chaos

import chaos.SetupHelper.getNodesUrlsFromFile
import linea.kotlin.toULong
import linea.log4j.configureLoggers
import linea.web3j.createWeb3jHttpClient
import maru.clients.beacon.Http4kBeaconChainClient
import net.consensys.linea.async.toSafeFuture
import net.consensys.linea.testing.filesystem.getPathTo
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tech.pegasys.teku.infrastructure.async.SafeFuture

class NodesSyncTest {
  private val log = LogManager.getLogger("maru.chaos.NodesSyncTest")

  fun getElNodeChainHead(elApiUrl: String): SafeFuture<ULong> {
    // createEthApiClient(elApiUrl, vertx = null, requestRetryConfig = null)
    //   .findBlockByNumber(B)
    return createWeb3jHttpClient(elApiUrl)
      .ethBlockNumber()
      .sendAsync()
      .toSafeFuture()
      .thenApply { it.blockNumber.toULong() }
  }

  private fun getElNodeChainHeads(nodes: List<NodeInfo<String>>): SafeFuture<List<NodeInfo<ULong>>> =
    nodes
      .map { node ->
        getElNodeChainHead(node.value)
          .thenApply { blockNumber -> node.map(blockNumber) }
      }.let { futures: List<SafeFuture<NodeInfo<ULong>>> ->
        SafeFuture.collectAll(futures.stream())
      }

  private fun getClBeaconChainHead(clApiUrl: String): SafeFuture<ULong> =
    Http4kBeaconChainClient(clApiUrl)
      .getBlock("head")
      .thenApply {
        it.data.message.slot
          .toULong()
      }

  private fun getClNodeChainHeads(nodes: List<NodeInfo<String>>): SafeFuture<List<NodeInfo<ULong>>> =
    nodes
      .map { node ->
        getClBeaconChainHead(node.value)
          .thenApply { blockNumber -> node.map(blockNumber) }
      }.let { futures: List<SafeFuture<NodeInfo<ULong>>> ->
        SafeFuture.collectAll(futures.stream())
      }

  private fun assertNodesAreInSync(
    nodesHeads: List<NodeInfo<ULong>>,
    outOfSyncLeniency: Int,
  ) {
    val maxHead = nodesHeads.maxOf { it.value }
    val minHead = nodesHeads.minOf { it.value }

    if (maxHead - minHead > outOfSyncLeniency.toULong()) {
      val nodeWithMinHead = nodesHeads.minBy { it.value }
      log.error(
        "Nodes are out of sync: maxHead={}, minHead={}, diff={}, leniency={}, nodeWithMinHead={}",
        maxHead,
        minHead,
        maxHead - minHead,
        outOfSyncLeniency,
        nodeWithMinHead.label,
      )
      nodesHeads
        .sortedBy { it.value }
        .reversed()
        .forEach { (node, headBlock) ->
          log.info("node={} headBlock={}", node, headBlock)
        }

      throw IllegalStateException("Nodes are out of sync: max head $maxHead, min head $minHead")
    }
  }

  @BeforeEach
  fun beforeEach() {
    configureLoggers(
      rootLevel = Level.INFO,
      "maru.chaos.NodesSyncTest" to Level.DEBUG,
    )
  }

  @Test
  fun `el nodes should be in sync`() {
    val nodesUrls =
      getNodesUrlsFromFile(
        getPathTo("tmp/port-forward-besu-8545.txt"),
      )
    val nodesHeads = getElNodeChainHeads(nodesUrls).get()
    assertNodesAreInSync(nodesHeads, outOfSyncLeniency = 3)
  }

  @Test
  fun `cl nodes should be in sync`() {
    val nodesUrls =
      getNodesUrlsFromFile(
        getPathTo("tmp/port-forward-maru-8080.txt"),
      )
    val nodesHeads = getClNodeChainHeads(nodesUrls).get()
    assertNodesAreInSync(nodesHeads, outOfSyncLeniency = 3)
  }
}
