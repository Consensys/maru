/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.test.cluster

import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import kotlin.time.Instant
import maru.app.MaruApp
import maru.config.MaruConfig
import maru.consensus.ChainFork
import maru.consensus.ClFork
import maru.consensus.ElFork
import maru.extensions.encodeHex
import maru.test.extensions.latestBlockNumber
import maru.test.genesis.GenesisFactory
import org.hyperledger.besu.tests.acceptance.dsl.node.BesuNode

enum class NodeRole {
  Sequencer,
  Follower,
  Bootnode,
}

fun NodeRole.isSequencer(): Boolean = this == NodeRole.Sequencer

fun NodeRole.isFollower(): Boolean = this == NodeRole.Follower

fun NodeRole.isBootnode(): Boolean = this == NodeRole.Bootnode

interface ElNode {
  fun start()

  fun stop()

  fun engineApiUrl(): String

  fun ethApiUrl(): String

  fun headBlockNumber(): ULong
}

data class ClusterNode(
  val maru: MaruApp,
  val nodeRole: NodeRole,
  val label: String = nodeRole.name,
  val elNode: ElNode?,
  val elFollowers: List<ElNode> = emptyList(),
) {
  init {
    require(nodeRole != NodeRole.Sequencer || elNode != null) {
      "Maru $nodeRole needs an payload validator defined"
    }
  }
}
typealias ElNodeBuilder = () -> ElNode

data class BesuElNode(
  val besu: BesuNode,
  val besuCluster: BesuCluster,
) : ElNode {
  override fun start() {
    besuCluster.addNodeAndStart(besu, awaitPeerDiscovery = false)
  }

  override fun stop() {
    besu.stop()
  }

  override fun engineApiUrl(): String = besu.engineRpcUrl().get()

  override fun ethApiUrl(): String = besu.jsonRpcBaseUrl().get()

  override fun headBlockNumber(): ULong = besu.latestBlockNumber()
}

enum class RunningState {
  STARTING,
  RUNNING,
  STOPPING,
  STOPPED,
}

fun createClusterDir(): Path =
  Files.createTempDirectory("maru-cluster-${Random.nextBytes(4).encodeHex(false)}").also {
    it.toFile().deleteOnExit()
  }

class MaruCluster(
  val chainId: UInt = Random.nextInt().toUInt(),
  val blockTimeSeconds: UInt = 1u,
  val terminalTotalDifficulty: ULong? = null,
  val chainForks: Map<Instant, ChainFork> =
    mapOf(
      Instant.fromEpochSeconds(0) to ChainFork(clFork = ClFork.QBFT_PHASE0, elFork = ElFork.Prague),
    ),
  val maruConfigTemplate: MaruConfig = configTemplate,
  val maruClusterDataDir: Path = createClusterDir(),
  val besuClusterWaitPeerDiscovery: Boolean = false,
  val besuCluster: BesuCluster = BesuCluster(),
) {
  val genesisFactory: GenesisFactory = GenesisFactory(chainId, blockTimeSeconds)
  private val nodesBuilders: MutableList<NodeBuilder> = mutableListOf()
  val nodes = mutableListOf<ClusterNode>()
  var runningState: RunningState = RunningState.STOPPED
    private set

  private fun createNodeBuilder(
    label: String,
    configurator: (NodeBuilder) -> Unit,
  ): NodeBuilder {
    assertUniqueLabel(label)
    return NodeBuilder(
      maruConfigTemplate = maruConfigTemplate,
      clusterDataDir = maruClusterDataDir,
      nodeLabel = label,
    ).also(configurator)
  }

  /**
   *  Add new node to the cluster configuration, before starting the cluster.
   */
  fun addNode(
    label: String,
    configurator: (NodeBuilder) -> Unit = {},
  ): MaruCluster {
    if (runningState != RunningState.STOPPED) {
      throw IllegalStateException("Cannot ADD NODE to MaruCluster that is $runningState. Please use addNodeAndStart()")
    }
    nodesBuilders.add(createNodeBuilder(label, configurator))
    return this
  }

  /**
   *  Add new node to already existing cluster and start it immediately.
   */
  fun addNewExtraNodeAndStart(
    nodeLabel: String,
    configurator: (NodeBuilder) -> Unit = {},
  ): MaruCluster {
    val nodeStatConfigs =
      createNodeBuilder(nodeLabel, configurator)
        .build(genesisFactory::besuGenesis, besuCluster)
    val node =
      buildClusterNode(
        nodeStartingConfig = nodeStatConfigs,
        booNodesEnrs = getBootnodesEnrs(),
      )
    nodes += node
    return this
  }

  fun addNode(
    role: NodeRole,
    configurator: (NodeBuilder) -> Unit = {},
  ): MaruCluster =
    addNode(label = role.name.lowercase()) { nodeBuilder ->
      nodeBuilder.withRole(role).let(configurator)
    }

  fun node(label: String): ClusterNode =
    nodes.firstOrNull { it.label == label }
      ?: throw IllegalArgumentException("No node with label=$label found in cluster")

  fun maruNode(label: String): MaruApp = node(label).maru

  fun maruNodes(labelPrefix: String): List<MaruApp> = nodes.filter { it.label.startsWith(labelPrefix) }.map { it.maru }

  fun maruNodes(role: NodeRole): List<MaruApp> = nodes.filter { it.nodeRole == role }.map { it.maru }

  fun elNode(label: String): ElNode? = node(label).elNode

  fun nodes(role: NodeRole): List<ClusterNode> = nodes.filter { it.nodeRole == role }

  fun besuNode(label: String): BesuNode = (node(label).elNode as BesuElNode).besu

  private fun startBesuNodes(nodesConfig: List<NodeBuilder.NodeBuildingConfig>) {
    nodesConfig.forEach { nodeStartConfig ->
      (listOf<ElNode?>(nodeStartConfig.elNode) + nodeStartConfig.elFollowers)
        .filter { it != null && it is BesuElNode }
        .forEach { elNode ->
          besuCluster.addNode((elNode as BesuElNode).besu)
        }
    }
    besuCluster.start(awaitPeerDiscovery = besuClusterWaitPeerDiscovery)
    println("\n\n=== Besu cluster started ===\n\n")
  }

  private fun getBootnodesEnrs(): List<String> =
    nodes
      .filter {
        it.nodeRole.isBootnode()
      }.map { it.maru.p2pNetwork.enr!! }

  fun start(): MaruCluster {
    if (runningState != RunningState.STOPPED) {
      throw IllegalStateException("Cannot START MaruCluster that is $runningState")
    }
    this.runningState = RunningState.STARTING
    val nodesStartConfigs = nodesBuilders.map { it.build(genesisFactory::besuGenesis, besuCluster) }

    val sequencersAddress =
      nodesStartConfigs
        .filter { it.nodeRole.isSequencer() }
        .map { it.nodeKey.address }

    // Initialize Maru with sequencers addresses, then init Besu Factory with ForkSchedule
    genesisFactory.initForkSchedule(
      sequencersAddress,
      terminalTotalDifficulty,
      chainForks,
    )

    // // Start Besu bootnodes first
    // startBesuNodes(nodesStartConfigs)

    // Build Maru nodes after Besu are started and we have their enode URLs
    // 1. we start bootnodes, to be able to get their enrs
    // 2. then we start the remaining nodes
    nodesStartConfigs
      .filter { it.nodeRole.isBootnode() }
      .forEach { nodeStartingConfig ->
        nodes += buildClusterNode(nodeStartingConfig, emptyList())
      }
    val bootNodesEnrs = getBootnodesEnrs()
    println("\n\n=== Maru bootnodes started: enrs=$bootNodesEnrs ===\n\n")
    nodesStartConfigs
      .filter { it.nodeRole != NodeRole.Bootnode }
      .forEach { nodeStartingConfig ->
        nodes += buildClusterNode(nodeStartingConfig, bootNodesEnrs)
      }

    this.runningState = RunningState.RUNNING
    nodesBuilders.clear()
    return this
  }

  fun buildClusterNode(
    nodeStartingConfig: NodeBuilder.NodeBuildingConfig,
    booNodesEnrs: List<String>,
  ): ClusterNode {
    startIfBesuNode(nodeStartingConfig.elNode)
    val maruApp =
      createMaru(
        elNode = nodeStartingConfig.elNode,
        config = nodeStartingConfig.maruConfig,
        bootnodes =
          nodeStartingConfig.overridingBootnodesNodesLables
            ?.let { nodesEnrs(it) }
            ?: booNodesEnrs,
        staticpeers = nodesAddr(nodeStartingConfig.staticPeersNodesLables ?: emptyList()),
        nodeKeyData = nodeStartingConfig.nodeKey,
        nodeRole = nodeStartingConfig.nodeRole,
        forkSchedule = genesisFactory.maruForkSchedule(),
      )

    maruApp.start()

    val clusterNode =
      ClusterNode(
        maru = maruApp,
        nodeRole = nodeStartingConfig.nodeRole,
        label = nodeStartingConfig.label,
        elNode = nodeStartingConfig.elNode,
        elFollowers = nodeStartingConfig.elFollowers,
      )
    return clusterNode
  }

  fun stop() {
    this.runningState = RunningState.STOPPING
    nodes.forEach {
      it.maru.close()
      it.maru.stop()
      it.elNode?.stop()
    }
    besuCluster.stop()
    this.runningState = RunningState.STOPPED
  }

  fun nodeCount(): Int = nodes.size

  private fun nodesEnrs(nodesLabels: List<String>): List<String> =
    nodesLabels.map { nodeLabel ->
      maruNode(nodeLabel).p2pNetwork.enr!!
    }

  private fun nodesAddr(nodesLabels: List<String>): List<String> =
    nodesLabels.map { nodeLabel ->
      val p2pNetwork = maruNode(nodeLabel).p2pNetwork
      "/ip4/127.0.0.1/tcp/${p2pNetwork.port}/p2p/${p2pNetwork.nodeId}"
        .also { println("node=$nodeLabel addr=$it") }
    }

  fun startIfBesuNode(elNode: ElNode?) {
    if (elNode is BesuElNode) {
      besuCluster.addNodeAndStart(elNode.besu, awaitPeerDiscovery = besuClusterWaitPeerDiscovery)
    }
  }

  fun assertUniqueLabel(nodeLabel: String) {
    val allNodesLabels = nodesBuilders.map { it.nodeLabel } + nodes.map { it.label }
    require(allNodesLabels == allNodesLabels.distinct()) {
      "Node label '$nodeLabel' is already used in the cluster. Please use unique labels for each node."
    }
  }
}
