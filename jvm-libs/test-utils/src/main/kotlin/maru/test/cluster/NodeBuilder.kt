/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.test.cluster

import java.nio.file.Path
import maru.config.MaruConfig
import maru.crypto.PrivateKeyGenerator
import testutils.besu.BesuFactory

class NodeBuilder(
  maruConfigTemplate: MaruConfig,
  var nodeLabel: String,
  val clusterDataDir: Path,
) {
  class NodeBuildingConfig(
    val nodeRole: NodeRole,
    val label: String = nodeRole.name,
    val maruConfig: MaruConfig,
    val nodeKey: PrivateKeyGenerator.KeyData,
    private val elNodeBuilder: ElNodeBuilder?,
    private val elFollowersBuilders: List<ElNodeBuilder> = emptyList(),
    // list of nodes lables, e.g bootnode-0, sequencer, ...
    val overridingBootnodesNodesLables: List<String>? = null,
    val staticPeersNodesLables: List<String>? = null,
  ) {
    val elNode: ElNode? by lazy {
      elNodeBuilder?.invoke()
    }
    val elFollowers: List<ElNode> by lazy {
      elFollowersBuilders.map { it.invoke() }
    }
  }

  private var elNodeBuilder: ElNodeBuilder? = null
  private lateinit var nodeRole: NodeRole
  private var overridingBootnodes: List<String>? = null
  private var staticPeers: List<String>? = null
  private var maruConfig: MaruConfig = updatePersistenceDataPath(nodeLabel, maruConfigTemplate)

  fun updatePersistenceDataPath(
    label: String,
    prevConfig: MaruConfig,
  ): MaruConfig =
    prevConfig.copy(
      persistence =
        prevConfig.persistence.copy(
          dataPath = clusterDataDir.resolve(label),
          privateKeyPath = clusterDataDir.resolve(label).resolve("private-key"),
        ),
    )

  // fun withBesu(besuBuilder: () -> BesuNode): NodeBuilder {
  //   this.elNodeBuilder = { BesuElNode(besu = besuBuilder(), besuCluster = besuCluster) }
  //   return this
  // }

  fun withRole(nodeRole: NodeRole): NodeBuilder {
    this.nodeRole = nodeRole
    return this
  }

  private fun getNodeRoleFromLabel(label: String?): NodeRole {
    if (label == null) {
      return NodeRole.Follower
    }
    return when {
      label.startsWith("sequencer", ignoreCase = true) -> NodeRole.Sequencer
      label.startsWith("validator", ignoreCase = true) -> NodeRole.Sequencer
      label.startsWith("bootnode", ignoreCase = true) -> NodeRole.Bootnode
      else -> NodeRole.Follower
    }
  }

  fun withLabel(label: String): NodeBuilder {
    this.nodeLabel = label
    this.maruConfig = updatePersistenceDataPath(nodeLabel, this.maruConfig)
    return this
  }

  fun bootnodes(nodesLabels: List<String>): NodeBuilder {
    this.overridingBootnodes = nodesLabels
    return this
  }

  fun staticPeers(nodesLabels: List<String>): NodeBuilder {
    this.staticPeers = nodesLabels
    return this
  }

  fun maruConfig(function: (MaruConfig) -> MaruConfig) {
    this.maruConfig = function(maruConfig)
  }

  private fun initBesuBuilderFunctionIfNecessary(
    besuGenesisProvider: () -> String,
    besuCluster: BesuCluster,
  ) {
    if (elNodeBuilder == null) {
      this.elNodeBuilder = {
        BesuElNode(
          BesuFactory.buildTestBesu(
            genesisFile = besuGenesisProvider(),
            validator = nodeRole.isSequencer(),
            nodeName = "$nodeLabel-besu",
          ),
          besuCluster = besuCluster,
        )
      }
    }
  }

  internal fun build(
    besuGenesisProvider: () -> String,
    besuCluster: BesuCluster,
  ): NodeBuildingConfig {
    initBesuBuilderFunctionIfNecessary(besuGenesisProvider, besuCluster)
    if (!this::nodeRole.isInitialized) {
      nodeRole = getNodeRoleFromLabel(this.nodeLabel)
    }

    return NodeBuildingConfig(
      maruConfig = this.maruConfig,
      elNodeBuilder = this.elNodeBuilder,
      nodeRole = nodeRole,
      label = nodeLabel,
      overridingBootnodesNodesLables = this.overridingBootnodes,
      staticPeersNodesLables = this.staticPeers,
      nodeKey = PrivateKeyGenerator.generatePrivateKey(),
    )
  }
}
