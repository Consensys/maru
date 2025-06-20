/*
   Copyright 2025 Consensys Software Inc.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */
package maru.p2p.discovery

import java.time.Duration
import java.util.function.Consumer
import maru.config.P2P
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

  val delayedExecutor =
    ScheduledExecutorAsyncRunner.create(
      "DiscoveryService",
      1,
      1,
      5,
      MetricTrackingExecutorFactory(NoOpMetricsSystem()),
    )
  var bootnodeRefreshTask: Cancellable? = null

  fun start() {
    val discoveryNetworkBuilder = DiscoverySystemBuilder()

    val bootnodes =
      p2pConfig.bootnodes
        .stream()
        .map { NodeRecordFactory.DEFAULT.fromEnr(it) }
        .toList()

    discoveryNetworkBuilder.listen(p2pConfig.ipAddress, p2pConfig.discoveryPort.toInt())
    discoveryNetworkBuilder.secretKey(privateKey)
    discoveryNetworkBuilder.localNodeRecord(localNodeRecord)
    discoveryNetworkBuilder.bootnodes(bootnodes)

    discoverySystem = discoveryNetworkBuilder.build()

    discoverySystem
      .start()
      .thenRun {
        // discoverySystem.updateCustomFieldValue(MaruForkId.MARU_FORK_ID_FIELD_NAME, maruForkId.encode())
        // TODO: do we want another custom field to identify topics/role/something else?
        this.bootnodeRefreshTask =
          delayedExecutor.runWithFixedDelay(
            { this.pingBootnodes(bootnodes) },
            BOOTNODE_REFRESH_DELAY,
            { error: Throwable? ->
              log.error(
                "Failed to contact discovery bootnodes",
                error,
              )
            },
          )
      }
  }

  fun stop() {
    bootnodeRefreshTask?.cancel()
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

  private fun convertNodeRecordToDiscoveryPeer(node: NodeRecord): MaruDiscoveryPeer =
    MaruDiscoveryPeer(
      (node.get(EnrField.PKEY_SECP256K1) as Bytes),
      node.nodeId,
      node.tcpAddress.get(),
      MaruForkId.fromNodeRecord(node),
    )

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
    val nodeRecordBuilder: NodeRecordBuilder =
      NodeRecordBuilder()
        .secretKey(privateKey)
        .seq(UInt64.ONE)
        .address(
          p2pConfig.ipAddress,
          p2pConfig.discoveryPort.toInt(),
          p2pConfig.port.toInt(),
        ).customField(MaruForkId.MARU_FORK_ID_FIELD_NAME, MaruForkId.MARU_INITIAL_FORK_ID.encode())
    return nodeRecordBuilder.build()
  }

  private fun peerIsOnTheRightChain(peer: MaruDiscoveryPeer): Boolean {
    return true; // TODO: check the fork id here
  }
}
