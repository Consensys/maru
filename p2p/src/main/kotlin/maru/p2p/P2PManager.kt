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
package maru.p2p

import io.libp2p.core.crypto.unmarshalPrivateKey
import java.nio.file.Path
import java.util.Optional
import java.util.concurrent.TimeUnit
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import tech.pegasys.teku.infrastructure.async.SafeFuture
import tech.pegasys.teku.networking.p2p.libp2p.MultiaddrPeerAddress
import tech.pegasys.teku.networking.p2p.libp2p.PeerAlreadyConnectedException
import tech.pegasys.teku.networking.p2p.network.P2PNetwork
import tech.pegasys.teku.networking.p2p.network.PeerAddress
import tech.pegasys.teku.networking.p2p.network.config.GeneratingFilePrivateKeySource
import tech.pegasys.teku.networking.p2p.peer.DisconnectReason
import tech.pegasys.teku.networking.p2p.peer.NodeId
import tech.pegasys.teku.networking.p2p.peer.Peer

class P2PManager(
  val privateKeyFile: Path,
  val ipAddress: String,
  val port: String,
  val staticPeers: List<String>,
) {
  companion object {
    private const val DEFAULT_RECONNECT_DELAY_MILLI_SECONDS = 5000L // TODO: Do we want to make this configurable?
  }

  private val log: Logger = LogManager.getLogger(this::class.java)
  private val delayedExecutor = SafeFuture.delayedExecutor(DEFAULT_RECONNECT_DELAY_MILLI_SECONDS, TimeUnit.MILLISECONDS)
  private val staticPeerMap = mutableMapOf<NodeId, MultiaddrPeerAddress>()

  lateinit var p2pNetwork: P2PNetwork<Peer>

  fun start() {
    p2pNetwork = buildP2PNetwork()

    p2pNetwork
      .start()
      ?.thenApply {
        staticPeers.forEach { peer ->
          p2pNetwork.createPeerAddress(peer)?.let { address -> addStaticPeer(address as MultiaddrPeerAddress) }
        }
      }?.get()
  }

  fun stop() {
    p2pNetwork.stop()
  }

  private fun buildP2PNetwork(): P2PNetwork<Peer> {
    val filePrivateKeySource = GeneratingFilePrivateKeySource(privateKeyFile.toString())
    // TODO: reading/generating this key should be done early on, as it is needed for the validator as well
    val privKeyBytes = filePrivateKeySource.privateKeyBytes
    val privateKey = unmarshalPrivateKey(privKeyBytes.toArrayUnsafe())

    return P2PNetworkFactory.build(
      privateKey = privateKey,
      ipAddress = ipAddress,
      port = port,
    )
  }

  fun addStaticPeer(peerAddress: MultiaddrPeerAddress) {
    if (peerAddress.id == p2pNetwork.nodeId) { // Don't connect to self
      return
    }
    synchronized(this) {
      if (staticPeerMap.containsKey(peerAddress.id)) {
        return
      }
      staticPeerMap.put(peerAddress.id, peerAddress) // This now works because staticPeers is mutable
    }
    maintainPersistentConnection(peerAddress)
  }

  fun removeStaticPeer(peerAddress: PeerAddress) {
    if (staticPeerMap.remove(peerAddress.id) != null) {
      p2pNetwork.getPeer(peerAddress.id).ifPresent { peer -> peer.disconnectImmediately(Optional.empty(), true) }
    }
  }

  private fun maintainPersistentConnection(peerAddress: MultiaddrPeerAddress): SafeFuture<Void> {
    val existingPeer = p2pNetwork.getPeer(peerAddress.id)
    if (existingPeer.isPresent) {
      log.debug("Already connected to peer {}", peerAddress)
      return SafeFuture.completedFuture(null)
    }
    return p2pNetwork
      .connect(peerAddress)
      .thenApply { peer: Peer ->
        peer.subscribeDisconnect { _: Optional<DisconnectReason?>?, _: Boolean ->
          run {
            if (staticPeerMap.contains(peerAddress.id)) {
              SafeFuture.runAsync({ maintainPersistentConnection(peerAddress) }, delayedExecutor)
            }
          }
        }
      }.thenRun({ log.info("Created persistent connection to {}", peerAddress) })
      .exceptionallyCompose {
        if (it is PeerAlreadyConnectedException) {
          log.info("Already connected to peer $peerAddress. Error: ${it.message}")
          SafeFuture.completedFuture(null)
        } else {
          log.trace(
            "Failed to connect to peer {}, retrying after {} ms. Error: {}",
            peerAddress,
            DEFAULT_RECONNECT_DELAY_MILLI_SECONDS,
            it.message,
          )
          SafeFuture.runAsync({ maintainPersistentConnection(peerAddress) }, delayedExecutor)
        }
      }
  }
}
