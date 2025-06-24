/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.p2p.discovery

import java.time.Duration
import java.util.Optional
import java.util.function.Consumer
import maru.config.P2P
import maru.config.consensus.ElFork
import maru.config.consensus.qbft.QbftConsensusConfig
import maru.consensus.ForkId
import maru.consensus.ForkSpec
import maru.serialization.ForkIdDeSer
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.apache.tuweni.bytes.Bytes
import org.apache.tuweni.crypto.SECP256K1.SecretKey
import org.apache.tuweni.units.bigints.UInt64
import org.ethereum.beacon.discovery.DiscoverySystem
import org.ethereum.beacon.discovery.DiscoverySystemBuilder
import org.ethereum.beacon.discovery.schema.EnrField
import org.ethereum.beacon.discovery.schema.NodeRecord
import org.ethereum.beacon.discovery.schema.NodeRecordBuilder
import org.ethereum.beacon.discovery.schema.NodeRecordFactory
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem
import tech.pegasys.teku.infrastructure.async.Cancellable
import tech.pegasys.teku.infrastructure.async.MetricTrackingExecutorFactory
import tech.pegasys.teku.infrastructure.async.SafeFuture
import tech.pegasys.teku.infrastructure.async.ScheduledExecutorAsyncRunner
import tech.pegasys.teku.networking.p2p.discovery.discv5.SecretKeyParser

class MaruDiscoveryService(
  privateKeyBytes: ByteArray,
  private val p2pConfig: P2P,
) {
  companion object {
    val BOOTNODE_REFRESH_DELAY: Duration = Duration.ofMinutes(2L)
  }

  private val log: Logger = LogManager.getLogger(this::javaClass)

  private lateinit var discoverySystem: DiscoverySystem

  private val privateKey = SecretKeyParser.fromLibP2pPrivKey(Bytes.wrap(privateKeyBytes))

  // add the fork id here as a custom field
  private val localNodeRecord = localNodeRecord(privateKey = privateKey, p2pConfig = p2pConfig)

  private val bootnodes =
    p2pConfig.bootnodes
      .stream()
      .map { NodeRecordFactory.DEFAULT.fromEnr(it) }
      .toList()

  val delayedExecutor =
    ScheduledExecutorAsyncRunner.create(
      "DiscoveryService",
      1,
      1,
      5,
      MetricTrackingExecutorFactory(NoOpMetricsSystem()),
    )
  private lateinit var bootnodeRefreshTask: Cancellable

  init {
    val discoveryNetworkBuilder = DiscoverySystemBuilder()

    discoveryNetworkBuilder.listen(p2pConfig.ipAddress, p2pConfig.discoveryPort.toInt())
    discoveryNetworkBuilder.secretKey(privateKey)
    discoveryNetworkBuilder.localNodeRecord(localNodeRecord)
    discoveryNetworkBuilder.bootnodes(bootnodes)

    discoverySystem = discoveryNetworkBuilder.build()
  }

  fun start() {
    discoverySystem
      .start()
      .thenRun {
        this.bootnodeRefreshTask =
          delayedExecutor.runWithFixedDelay(
            { this.pingBootnodes(bootnodes) },
            BOOTNODE_REFRESH_DELAY,
            { error: Throwable ->
              log.error(
                "Failed to contact discovery bootnodes",
                error,
              )
            },
          )
      }
  }

  fun stop() {
    bootnodeRefreshTask.cancel()
    discoverySystem.stop()
  }

  fun searchForPeers(): SafeFuture<Collection<MaruDiscoveryPeer>> =
    SafeFuture
      .of(discoverySystem.searchForNewPeers())
      // Current version of discovery doesn't return the found peers but next version will
      .thenApply { getKnownPeers() }

  fun getKnownPeers(): Collection<MaruDiscoveryPeer> =
    discoverySystem
      .streamLiveNodes()
      .map { node: NodeRecord ->
        convertNodeRecordToDiscoveryPeer(node)
      }.filter { peerIsOnTheRightChain(it) }
      .toList()

  fun getLocalNodeRecord(): NodeRecord = localNodeRecord

  private fun convertNodeRecordToDiscoveryPeer(node: NodeRecord): MaruDiscoveryPeer {
    val forkIdBytes = node.get(ForkId.FORK_ID_FIELD_NAME)
    val forkId =
      (forkIdBytes as? Bytes)?.toArray()?.let {
        Optional.of(ForkIdDeSer.ForkIdDeserializer.deserialize(it))
      } ?: Optional.empty<ForkId>()
    return MaruDiscoveryPeer(
      (node.get(EnrField.PKEY_SECP256K1) as Bytes),
      node.nodeId,
      node.tcpAddress.get(),
      forkId,
    )
  }

  private fun pingBootnodes(bootnodeRecords: List<NodeRecord>) {
    bootnodeRecords.forEach(
      Consumer { bootnode: NodeRecord? ->
        SafeFuture
          .of(discoverySystem.ping(bootnode))
          .exceptionally {
            log.info("Bootnode {} is unresponsive", bootnode)
            throw it
          }
      },
    )
  }

  private fun localNodeRecord(
    privateKey: SecretKey,
    p2pConfig: P2P,
  ): NodeRecord {
    val qbftConsensusConfig =
      QbftConsensusConfig(
        validatorSet = emptySet(),
        ElFork.Prague,
      )
    val forkId =
      ForkId(
        chainId = 1L.toUInt(),
        forkSpec =
          ForkSpec(
            blockTimeSeconds = 15,
            timestampSeconds = 0L,
            configuration = qbftConsensusConfig,
          ),
        genesisRootHash = ByteArray(32),
      )
    val nodeRecordBuilder: NodeRecordBuilder =
      NodeRecordBuilder()
        .secretKey(privateKey)
        .seq(UInt64.ONE)
        .address(
          p2pConfig.ipAddress,
          p2pConfig.discoveryPort.toInt(),
          p2pConfig.port.toInt(),
        ).customField(ForkId.FORK_ID_FIELD_NAME, Bytes.wrap(ForkIdDeSer.ForkIdSerializer.serialize(forkId)))
    // TODO: do we want more custom fields to identify topics/role/something else?

    return nodeRecordBuilder.build()
  }

  private fun peerIsOnTheRightChain(peer: MaruDiscoveryPeer): Boolean {
    return true; // TODO: check the fork id here
  }
}
