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
import maru.consensus.ForkId
import maru.consensus.ForkId.Companion.FORK_ID_FIELD_NAME
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.apache.tuweni.bytes.Bytes
import org.apache.tuweni.units.bigints.UInt64
import org.ethereum.beacon.discovery.DiscoverySystem
import org.ethereum.beacon.discovery.DiscoverySystemBuilder
import org.ethereum.beacon.discovery.schema.EnrField
import org.ethereum.beacon.discovery.schema.NodeRecord
import org.ethereum.beacon.discovery.schema.NodeRecordBuilder
import org.ethereum.beacon.discovery.schema.NodeRecordFactory
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem
import tech.pegasys.teku.infrastructure.async.AsyncRunner
import tech.pegasys.teku.infrastructure.async.Cancellable
import tech.pegasys.teku.infrastructure.async.MetricTrackingExecutorFactory
import tech.pegasys.teku.infrastructure.async.SafeFuture
import tech.pegasys.teku.infrastructure.async.ScheduledExecutorAsyncRunner
import tech.pegasys.teku.networking.p2p.discovery.discv5.SecretKeyParser

class MaruDiscoveryService(
  privateKeyBytes: ByteArray,
  private val p2pConfig: P2P,
  private val forkIdProvider: () -> ForkId, // returns the ForkId of the tip of the chain
) {
  companion object {
    val BOOTNODE_REFRESH_DELAY: Duration = Duration.ofMinutes(2L)
  }

  private val log: Logger = LogManager.getLogger(this::javaClass)

  private var discoverySystem: DiscoverySystem

  private val privateKey = SecretKeyParser.fromLibP2pPrivKey(Bytes.wrap(privateKeyBytes))

  private val bootnodes =
    p2pConfig.bootnodes
      .stream()
      .map { NodeRecordFactory.DEFAULT.fromEnr(it) }
      .toList()

  val delayedExecutor: AsyncRunner =
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
    discoveryNetworkBuilder.localNodeRecord(localNodeRecord())
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

  fun updateForkId(forkId: ForkId) { // TODO: Need to call this when the fork id changes
    discoverySystem.updateCustomFieldValue(
      FORK_ID_FIELD_NAME,
      forkId.bytes,
    )
  }

  fun searchForPeers(): SafeFuture<Collection<MaruDiscoveryPeer>> =
    SafeFuture
      .of(discoverySystem.searchForNewPeers())
      // The current version of discovery doesn't return the found peers but next version will
      .thenApply { getKnownPeers() }

  fun getKnownPeers(): Collection<MaruDiscoveryPeer> =
    discoverySystem
      .streamLiveNodes()
      .map { node: NodeRecord ->
        convertNodeRecordToDiscoveryPeer(node)
      }.filter { checkPeer(it) }
      .toList()

  fun getLocalNodeRecord(): NodeRecord = discoverySystem.localNodeRecord

  private fun convertNodeRecordToDiscoveryPeer(node: NodeRecord): MaruDiscoveryPeer {
    val forkIdBytes = node.get(FORK_ID_FIELD_NAME) as? Bytes?
    return MaruDiscoveryPeer(
      (node.get(EnrField.PKEY_SECP256K1) as? Bytes),
      node.nodeId,
      node.tcpAddress.orElse(null),
      Optional.ofNullable(forkIdBytes),
    )
  }

  private fun pingBootnodes(bootnodeRecords: List<NodeRecord>) {
    bootnodeRecords.forEach(
      Consumer { bootnode: NodeRecord? ->
        SafeFuture
          .of(discoverySystem.ping(bootnode))
          .whenComplete { _, e ->
            if (e != null) {
              log.warn("Bootnode {} is unresponsive", bootnode)
            }
          }
      },
    )
  }

  private fun localNodeRecord(): NodeRecord {
    val nodeRecordBuilder: NodeRecordBuilder =
      NodeRecordBuilder()
        .secretKey(privateKey)
        .seq(UInt64.ONE)
        .address(
          p2pConfig.ipAddress,
          p2pConfig.discoveryPort.toInt(),
          p2pConfig.port.toInt(),
        ).customField(FORK_ID_FIELD_NAME, forkIdProvider.invoke().bytes)
    // TODO: do we want more custom fields to identify version/topics/role/something else?

    return nodeRecordBuilder.build()
  }

  private fun checkPeer(peer: MaruDiscoveryPeer): Boolean {
    if (peer.nodeIdBytes == null ||
      peer.publicKey == null ||
      peer.addr == null ||
      peer.forkIdBytes.isEmpty
    ) {
      return false
    }
    if (peer.forkIdBytes.get() != forkIdProvider.invoke().bytes) {
      log.debug(
        "Peer {} is on a different chain. Expected: {}, Found: {}",
        peer.nodeId,
        forkIdProvider.invoke().bytes,
        peer.forkIdBytes.get(),
      )
      return false
    } else {
      return true
    }
  }
}
