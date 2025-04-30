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
import io.libp2p.core.crypto.unmarshalPrivateKey
import java.util.Optional
import java.util.concurrent.TimeUnit
import maru.p2p.P2PNetworkFactory
import maru.p2p.TestTopicHandler
import okhttp3.internal.toLongOrDefault
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import tech.pegasys.teku.infrastructure.async.SafeFuture
import tech.pegasys.teku.networking.p2p.libp2p.PeerAlreadyConnectedException
import tech.pegasys.teku.networking.p2p.network.P2PNetwork
import tech.pegasys.teku.networking.p2p.network.PeerAddress
import tech.pegasys.teku.networking.p2p.network.config.GeneratingFilePrivateKeySource
import tech.pegasys.teku.networking.p2p.peer.DisconnectReason
import tech.pegasys.teku.networking.p2p.peer.NodeId
import tech.pegasys.teku.networking.p2p.peer.Peer

class P2PManager {
  companion object {
    private const val DEFAULT_RECONNECT_DELAY_MILLI_SECONDS = 5000L
  }

  private val reconnectDelayInMilliSeconds: Long =
    System
      .getProperty("maru.reconnect.delay")
      .toLongOrDefault(DEFAULT_RECONNECT_DELAY_MILLI_SECONDS)

  private val log: Logger = LogManager.getLogger(this::class.java)
  private val delayedExecutor = SafeFuture.delayedExecutor(reconnectDelayInMilliSeconds, TimeUnit.MILLISECONDS)
  private val staticPeers: MutableList<NodeId> = mutableListOf()

  var p2pNetwork: P2PNetwork<Peer>? = null

  fun start(
    staticPeers: List<String>,
    privateKeyFile: String?,
    networks: List<String>?,
  ) {
    p2pNetwork = buildP2PNetwork(privateKeyFile, networks)

    p2pNetwork
      ?.start()
      ?.thenApply {
        staticPeers.forEach { peer ->
          p2pNetwork?.createPeerAddress(peer)?.let { address -> addStaticPeer(address) }
        }
        p2pNetwork?.subscribe("topic", TestTopicHandler())
      }?.get()
  }

  fun stop() {
    p2pNetwork?.stop()
  }

  private fun buildP2PNetwork(
    privateKeyFile: String?,
    networks: List<String>?,
  ): P2PNetwork<Peer> {
    val filePrivateKeySource = GeneratingFilePrivateKeySource(privateKeyFile!!)
    val privKeyBytes = filePrivateKeySource.privateKeyBytes
    val privateKey = unmarshalPrivateKey(privKeyBytes.toArrayUnsafe())

    return P2PNetworkFactory.build(
      privateKey = privateKey,
      networks = networks!!,
    )
  }

  fun addStaticPeer(peerAddress: PeerAddress) {
    if (peerAddress.id == p2pNetwork?.nodeId) {
      return
    }
    synchronized(this) {
      if (staticPeers.contains(peerAddress.id)) {
        return
      }
      staticPeers.add(peerAddress.id)
    }
    maintainPersistentConnection(peerAddress)
  }

  fun removeStaticPeer(peerAddress: PeerAddress) {
    if (staticPeers.remove(peerAddress.id)) {
      p2pNetwork!!.getPeer(peerAddress.id).ifPresent { peer -> peer.disconnectImmediately(Optional.empty(), true) }
    }
  }

  private fun maintainPersistentConnection(peerAddress: PeerAddress): SafeFuture<Void> {
    val existingPeer = p2pNetwork!!.getPeer(peerAddress.id)
    if (existingPeer.isPresent) {
      log.debug("Already connected to peer {}", peerAddress)
      return SafeFuture.completedFuture(null)
    }
    return p2pNetwork!!
      .connect(peerAddress)
      .thenApply { peer: Peer ->
        peer.subscribeDisconnect { _: Optional<DisconnectReason?>?, _: Boolean ->
          run {
            if (staticPeers.contains(peerAddress.id)) {
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
            reconnectDelayInMilliSeconds,
            it.message,
          )
          SafeFuture.runAsync({ maintainPersistentConnection(peerAddress) }, delayedExecutor)
        }
      }
  }
}
