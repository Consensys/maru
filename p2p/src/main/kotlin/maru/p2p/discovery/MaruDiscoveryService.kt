/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.p2p.discovery

import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import maru.config.P2P
import maru.consensus.ForkIdHashProvider
import net.consensys.linea.async.toSafeFuture
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.apache.tuweni.bytes.Bytes
import org.apache.tuweni.units.bigints.UInt64
import org.ethereum.beacon.discovery.DiscoverySystemBuilder
import org.ethereum.beacon.discovery.MutableDiscoverySystem
import org.ethereum.beacon.discovery.schema.EnrField
import org.ethereum.beacon.discovery.schema.NodeRecord
import org.ethereum.beacon.discovery.schema.NodeRecordBuilder
import org.ethereum.beacon.discovery.schema.NodeRecordFactory
import org.hyperledger.besu.plugin.services.MetricsSystem
import tech.pegasys.teku.infrastructure.async.AsyncRunner
import tech.pegasys.teku.infrastructure.async.Cancellable
import tech.pegasys.teku.infrastructure.async.MetricTrackingExecutorFactory
import tech.pegasys.teku.infrastructure.async.SafeFuture
import tech.pegasys.teku.infrastructure.async.ScheduledExecutorAsyncRunner
import tech.pegasys.teku.networking.p2p.discovery.discv5.SecretKeyParser

class MaruDiscoveryService(
  privateKeyBytes: ByteArray,
  private val p2pConfig: P2P,
  private val forkIdHashProvider: ForkIdHashProvider,
  metricsSystem: MetricsSystem,
) {
  init {
    require(p2pConfig.discovery != null) {
      "MaruDiscoveryService is being initialized without the discovery section in the P2P config!"
    }
  }

  companion object {
    const val FORK_ID_HASH_FIELD_NAME = "eth2"
    const val DISCOVERY_TIMEOUT_SECONDS = 30L
  }

  private val log: Logger = LogManager.getLogger(this.javaClass)

  private val privateKey = SecretKeyParser.fromLibP2pPrivKey(Bytes.wrap(privateKeyBytes))

  private val bootnodes =
    p2pConfig.discovery!!
      .bootnodes
      .map { NodeRecordFactory.DEFAULT.fromEnr(it) }
      .toList()

  val discoverySystem: MutableDiscoverySystem =
    DiscoverySystemBuilder()
      .listen(p2pConfig.ipAddress, p2pConfig.discovery!!.port.toInt())
      .secretKey(privateKey)
      .localNodeRecord(createLocalNodeRecord())
      .bootnodes(bootnodes)
      .buildMutable()

  val delayedExecutor: AsyncRunner =
    ScheduledExecutorAsyncRunner.create(
      "DiscoveryService",
      1,
      1,
      5,
      MetricTrackingExecutorFactory(metricsSystem),
    )

  private lateinit var bootnodeRefreshTask: Cancellable

  fun getLocalNodeRecord(): NodeRecord = discoverySystem.getLocalNodeRecord()

  fun start() {
    discoverySystem
      .start()
      .thenRun {
        this.bootnodeRefreshTask =
          delayedExecutor.runWithFixedDelay(
            { this.pingBootnodes(bootnodes) },
            0.seconds.toJavaDuration(),
            p2pConfig.discovery!!.refreshInterval.toJavaDuration(),
            { error: Throwable ->
              log.error(
                "Failed to contact discovery bootnodes",
                error,
              )
            },
          )
      }.get(DISCOVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    return
  }

  fun stop() {
    bootnodeRefreshTask.cancel()
    discoverySystem.stop()
  }

  fun updateForkIdHash(forkIdHash: Bytes) { // TODO: Need to call this when the fork id changes
    discoverySystem.updateCustomFieldValue(
      FORK_ID_HASH_FIELD_NAME,
      forkIdHash,
    )
  }

  fun searchForPeers(): SafeFuture<Collection<MaruDiscoveryPeer>> =
    discoverySystem
      .searchForNewPeers()
      // The current version of discovery doesn't return the found peers but next version will
      .toSafeFuture()
      .thenApply { getKnownPeers() }

  fun getKnownPeers(): Collection<MaruDiscoveryPeer> =
    discoverySystem
      .streamLiveNodes()
      .filter(this::checkNodeRecord)
      .map { node: NodeRecord ->
        convertSafeNodeRecordToDiscoveryPeer(node)
      }.toList()
      .toSet()

  private fun convertSafeNodeRecordToDiscoveryPeer(node: NodeRecord): MaruDiscoveryPeer {
    // node record has been checked in checkNodeRecord, so we can convert to MaruDiscoveryPeer safely
    return MaruDiscoveryPeer(
      publicKeyBytes = (node.get(EnrField.PKEY_SECP256K1) as Bytes),
      nodeIdBytes = node.nodeId,
      addr = node.tcpAddress.get(),
      forkIdBytes = node.get(FORK_ID_HASH_FIELD_NAME) as Bytes,
    )
  }

  private fun checkNodeRecord(node: NodeRecord): Boolean {
    if (node.get(FORK_ID_HASH_FIELD_NAME) == null) {
      log.trace("Node record is missing forkId field: {}", node)
      return false
    }
    val forkId =
      (node.get(FORK_ID_HASH_FIELD_NAME) as? Bytes) ?: run {
        log.trace("Failed to cast value for the forkId hash to Bytes")
        return false
      }
    if (forkId != Bytes.wrap(forkIdHashProvider.currentForkIdHash())) {
      log.trace(
        "Peer {} is on a different chain. Expected: {}, Found: {}",
        node.nodeId,
        Bytes.wrap(forkIdHashProvider.currentForkIdHash()),
        forkId,
      )
      return false
    }
    if (node.get(EnrField.PKEY_SECP256K1) == null) {
      log.trace("Node record is missing public key field: {}", node)
      return false
    }
    (node.get(EnrField.PKEY_SECP256K1) as? Bytes) ?: run {
      log.trace("Failed to cast value for the public key to Bytes")
      return false
    }
    if (node.tcpAddress.isEmpty) {
      log.trace(
        "node record doesn't have a TCP address: {}",
        node,
      )
      return false
    }
    return true
  }

  private fun pingBootnodes(bootnodeRecords: List<NodeRecord>) {
    log.trace("Pinging bootnodes")
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

  private fun createLocalNodeRecord(): NodeRecord {
    val nodeRecordBuilder: NodeRecordBuilder =
      NodeRecordBuilder()
        .secretKey(privateKey)
        .seq(UInt64.ONE)
        .address(
          p2pConfig.ipAddress,
          p2pConfig.discovery!!.port.toInt(),
          p2pConfig.port.toInt(),
        ).customField(FORK_ID_HASH_FIELD_NAME, Bytes.wrap(forkIdHashProvider.currentForkIdHash()))
    // TODO: do we want more custom fields to identify version/topics/role/something else?

    return nodeRecordBuilder.build()
  }
}
