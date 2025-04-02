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
package maru.app

import io.libp2p.core.crypto.unmarshalPrivateKey
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multiformats.Protocol
import io.libp2p.core.pubsub.ValidationResult
import java.time.Clock
import java.util.Optional
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import maru.app.config.MaruConfig
import maru.consensus.ForksSchedule
import maru.p2p.P2PNetworkBuilder
import okhttp3.internal.toLongOrDefault
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.apache.tuweni.bytes.Bytes
import tech.pegasys.teku.infrastructure.async.SafeFuture
import tech.pegasys.teku.networking.p2p.gossip.PreparedGossipMessage
import tech.pegasys.teku.networking.p2p.gossip.TopicHandler
import tech.pegasys.teku.networking.p2p.libp2p.PeerAlreadyConnectedException
import tech.pegasys.teku.networking.p2p.network.P2PNetwork
import tech.pegasys.teku.networking.p2p.network.PeerAddress
import tech.pegasys.teku.networking.p2p.network.config.GeneratingFilePrivateKeySource
import tech.pegasys.teku.networking.p2p.peer.DisconnectReason
import tech.pegasys.teku.networking.p2p.peer.NodeId
import tech.pegasys.teku.networking.p2p.peer.Peer

private const val ORIGINAL_MESSAGE = "68656c6c6f"

class MaruApp(
  val config: MaruConfig,
  beaconGenesisConfig: ForksSchedule,
  clock: Clock = Clock.systemUTC(),
) {
  companion object {
    const val DEFAULT_RECONNECT_DELAY_MILLI_SECONDS = 20L
  }

  private val reconnectDelayInMilliSeconds: Long =
    System
      .getProperty(
        "maru.reconnect.delay",
      ).toLongOrDefault(DEFAULT_RECONNECT_DELAY_MILLI_SECONDS)

  private val log: Logger = LogManager.getLogger(this::class.java)
  private val delayedExecutor = SafeFuture.delayedExecutor(reconnectDelayInMilliSeconds, TimeUnit.MILLISECONDS)
  private var p2PNetwork: P2PNetwork<Peer>? = null
  private var isRunning: AtomicBoolean = AtomicBoolean(false)
  private val staticPeers: MutableList<NodeId> = mutableListOf()

  init {
    if (config.p2pConfig == null) {
      log.warn("P2P is disabled!")
    }
    if (config.validator == null) {
      log.info("Maru is running in follower-only node")
    }
  }

  private val eventProducer =
    DummyConsensusProtocolBuilder.build(
      forksSchedule = beaconGenesisConfig,
      clock = clock,
      executionClientConfig = config.executionClientConfig,
      dummyConsensusOptions = config.dummyConsensusOptions!!,
    )

  fun start() {
    p2PNetwork = buildP2PNetwork()

    p2PNetwork
      ?.start()
      ?.thenApply {
        // connect to static peers
        config.p2pConfig?.staticPeers?.forEach { peer ->
          p2PNetwork?.createPeerAddress(peer)?.let { address -> addStaticPeer(address) }
        }
//      p2PNetwork?.subscribe("topic", TestTopicHandler())

        eventProducer.start()
      }?.thenApply {
        log.info("Maru is up")
      }?.get()
    isRunning.set(true)
  }

  fun stop() {
    eventProducer.stop()
    p2PNetwork?.stop()
    isRunning.set(false)
  }

  private fun buildP2PNetwork(): P2PNetwork<Peer> {
    val networkBuilder = P2PNetworkBuilder()

    val filePrivateKeySource = GeneratingFilePrivateKeySource(config.p2pConfig?.privateKeyFile!!)
    val privKeyBytes = filePrivateKeySource.privateKeyBytes
    val privateKey = unmarshalPrivateKey(privKeyBytes.toArrayUnsafe())

    networkBuilder.privateKey(privateKey)

    val networks = mutableMapOf<String, Multiaddr>()
    for (i in 0..config.p2pConfig.networks.size - 1) {
      val addr = Multiaddr(config.p2pConfig.networks[i])
      if (addr.components
          .stream()
          .filter({ c -> c.protocol.equals(Protocol.IP4) })
          .findAny()
          .isPresent
      ) {
        networks.put("ipv4", addr)
        networkBuilder.ipv4Network(addr)
      } else if (addr.components
          .stream()
          .filter({ c -> c.protocol.equals(Protocol.IP6) })
          .findAny()
          .isPresent
      ) {
        networks.put("ipv6", addr)
        networkBuilder.ipv6Network(addr)
      } else {
        throw IllegalArgumentException("Only IPv4 and IPv6 networks are allowed.")
      }
    }
    if (networks.isEmpty()) {
      throw IllegalArgumentException("No IPv4 or IPv6 network interface found.")
    } else if (networks.size != config.p2pConfig.networks.size) {
      throw IllegalArgumentException("One of each network type, IPv4 and IPv6, is allowed.")
    }

    val network = networkBuilder.build()

    return network
  }

  class TestTopicHandler : TopicHandler {
    override fun prepareMessage(
      payload: Bytes?,
      arrivalTimestamp: Optional<tech.pegasys.teku.infrastructure.unsigned.UInt64>?,
    ): PreparedGossipMessage {
      TODO("Not yet implemented")
    }

    override fun handleMessage(message: PreparedGossipMessage?): SafeFuture<ValidationResult> {
      var data: Bytes? = null
      message.let {
        data = message!!.originalMessage
      }
      if (data!!.equals(ORIGINAL_MESSAGE)) {
        return SafeFuture.completedFuture(ValidationResult.Valid)
      } else {
        return SafeFuture.completedFuture(ValidationResult.Invalid)
      }
    }

    override fun getMaxMessageSize(): Int = 43434343
  }

  fun getP2PNetwork(): P2PNetwork<Peer>? = p2PNetwork

  fun addStaticPeer(peerAddress: PeerAddress) {
    if (peerAddress.id == p2PNetwork?.nodeId) {
      // TODO: log something here because we are trying to add ourselves as a static peer
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
      p2PNetwork!!.getPeer(peerAddress.id).ifPresent { peer -> peer.disconnectImmediately(Optional.empty(), true) }
    } else {
      // TODO: log something here if we are trying to remove a peer that is not in the static peers list?
    }
  }

  private fun maintainPersistentConnection(peerAddress: PeerAddress): SafeFuture<Void> {
    val existingPeer = p2PNetwork!!.getPeer(peerAddress.id)
    if (existingPeer.isPresent) {
      log.debug("Already connected to peer {}", peerAddress)
      return SafeFuture.completedFuture(null)
    }
    return p2PNetwork!!
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
