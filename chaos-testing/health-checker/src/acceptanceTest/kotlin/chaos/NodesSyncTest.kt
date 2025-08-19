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
import linea.web3j.createWeb3jHttpClient
import net.consensys.linea.async.toSafeFuture
import net.consensys.linea.testing.filesystem.getPathTo
import org.apache.logging.log4j.LogManager
import org.junit.jupiter.api.Test
import tech.pegasys.teku.infrastructure.async.SafeFuture

class NodesSyncTest {
  private val log = LogManager.getLogger(NodesSyncTest::class.java)

  fun getNodeChainHead(elApiUrl: String): SafeFuture<ULong> {
    // createEthApiClient(elApiUrl, vertx = null, requestRetryConfig = null)
    //   .findBlockByNumber(B)
    return createWeb3jHttpClient(elApiUrl)
      .ethBlockNumber()
      .sendAsync()
      .toSafeFuture()
      .thenApply { it.blockNumber.toULong() }
  }

  private fun getNodeChainHeads(nodes: List<NodeInfo<String>>): SafeFuture<List<NodeInfo<ULong>>> =
    nodes
      .map { node ->
        getNodeChainHead(node.value)
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
        "Nodes are out of sync: maxHead{}, minHead={}, diff={}, leniency={}, nodeWithMinHead={}",
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

  @Test
  fun `nodes should be in sync`() {
    val nodesUrls =
      getNodesUrlsFromFile(
        getPathTo("tmp/port-forward-besu-8545.txt"),
      )
    val nodesHeads = getNodeChainHeads(nodesUrls).get()
    assertNodesAreInSync(nodesHeads, outOfSyncLeniency = 3)
  }
}
