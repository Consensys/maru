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
import java.util.Optional
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier
import maru.config.P2P
import maru.core.SealedBeaconBlock
import maru.serialization.Serializer
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.apache.tuweni.bytes.Bytes
import tech.pegasys.teku.infrastructure.async.SafeFuture
import tech.pegasys.teku.networking.p2p.libp2p.MultiaddrPeerAddress
import tech.pegasys.teku.networking.p2p.libp2p.PeerAlreadyConnectedException
import tech.pegasys.teku.networking.p2p.network.PeerAddress
import tech.pegasys.teku.networking.p2p.peer.DisconnectReason
import tech.pegasys.teku.networking.p2p.peer.NodeId
import tech.pegasys.teku.networking.p2p.peer.Peer
import tech.pegasys.teku.networking.p2p.network.P2PNetwork as TekuP2PNetwork

class SubscriptionManager<E> {
  private val log = LogManager.getLogger(this.javaClass)
  private val nextSubscriptionId = AtomicInteger()
  private val subscriptions: MutableMap<Int, (E) -> SafeFuture<ValidationResult>> = mutableMapOf()

  @Synchronized
  fun subscribeToBlocks(subscriber: (E) -> SafeFuture<ValidationResult>): Int {
    val subscriptionId = nextSubscriptionId.get()
    subscriptions[subscriptionId] = subscriber
    nextSubscriptionId.incrementAndGet()
    return subscriptionId
  }

  @Synchronized
  fun unsubscribe(subscriptionId: Int) {
    subscriptions.remove(subscriptionId)
  }

  fun handleEvent(event: E): SafeFuture<ValidationResult> {
    val handlerFutures =
      subscriptions.map { (subscriptionId, handler) ->
        try {
          handler(event)
        } catch (th: Throwable) {
          log.debug(
            Supplier<String> { "Error from subscription=$subscriptionId while handling event=$event!" },
            th,
          )
          SafeFuture.failedFuture(th)
        }
      }
    return SafeFuture.collectAll(handlerFutures.stream()).thenApply {
      it.reduce { acc: ValidationResult, next: ValidationResult ->
        when {
          acc is ValidationResult.Companion.Failed -> acc
          next is ValidationResult.Companion.Failed -> next
          acc is ValidationResult.Companion.KindaFine -> acc
          next is ValidationResult.Companion.KindaFine -> next
          else -> acc
        }
      }
    }
  }
}

class P2PNetworkImpl(
  privateKeyBytes: ByteArray,
  private val p2pConfig: P2P,
  chainId: UInt,
  private val serializer: Serializer<SealedBeaconBlock>,
) : P2PNetwork {
  private val topicIdGenerator = LineaTopicIdGenerator(chainId)

  private fun buildP2PNetwork(
    privateKeyBytes: ByteArray,
    p2pConfig: P2P,
    serializer: Serializer<SealedBeaconBlock>,
  ): TekuP2PNetwork<Peer> {
    val privateKey = unmarshalPrivateKey(privateKeyBytes)

    return Libp2pNetworkFactory.build(
      privateKey = privateKey,
      ipAddress = p2pConfig.ipAddress,
      port = p2pConfig.port,
      sealedBlocksSubscriptionManager = SubscriptionManager(),
      serializer = serializer,
      topicIdGenerator = topicIdGenerator,
    )
  }

  private val subscriptionManager = SubscriptionManager<SealedBeaconBlock>()

  val p2pNetwork: TekuP2PNetwork<Peer> = buildP2PNetwork(privateKeyBytes, p2pConfig, serializer)

  private val log: Logger = LogManager.getLogger(this::class.java)
  private val delayedExecutor =
    SafeFuture.delayedExecutor(p2pConfig.reconnectDelay.inWholeMilliseconds, TimeUnit.MILLISECONDS)
  private val staticPeerMap = mutableMapOf<NodeId, MultiaddrPeerAddress>()

  override fun start(): SafeFuture<Unit> =
    p2pNetwork
      .start()
      .thenApply {
        p2pConfig.staticPeers.forEach { peer ->
          p2pNetwork
            .createPeerAddress(peer)
            ?.let { address -> addStaticPeer(address as MultiaddrPeerAddress) }
        }
      }

  override fun stop(): SafeFuture<Unit> = p2pNetwork.stop().thenApply { }

  override fun broadcastMessage(message: Message<*>) {
    when (message.type) {
      MessageType.QBFT -> Unit // TODO: Add QBFT messages support later
      MessageType.BLOCK -> {
        require(message.payload is SealedBeaconBlock)
        val serializedSealedBeaconBlock = Bytes.wrap(serializer.serialize(message.payload as SealedBeaconBlock))
        p2pNetwork.gossip(topicIdGenerator.topicId(message.type, message.version), serializedSealedBeaconBlock)
      }
    }
  }

  override fun subscribeToBlocks(subscriber: SealedBeaconBlockHandler<ValidationResult>): Int =
    subscriptionManager.subscribeToBlocks(subscriber::handleSealedBlock)

  override fun unsubscribe(subscriptionId: Int) = subscriptionManager.unsubscribe(subscriptionId)

  fun addStaticPeer(peerAddress: MultiaddrPeerAddress) {
    if (peerAddress.id == p2pNetwork.nodeId) { // Don't connect to self
      return
    }
    synchronized(this) {
      if (staticPeerMap.containsKey(peerAddress.id)) {
        return
      }
      staticPeerMap[peerAddress.id] = peerAddress
    }
    maintainPersistentConnection(peerAddress)
  }

  fun removeStaticPeer(peerAddress: PeerAddress) {
    synchronized(this) {
      staticPeerMap.remove(peerAddress.id)
      p2pNetwork.getPeer(peerAddress.id).ifPresent { peer -> peer.disconnectImmediately(Optional.empty(), true) }
    }
  }

  private fun maintainPersistentConnection(peerAddress: MultiaddrPeerAddress): SafeFuture<Unit> =
    p2pNetwork
      .connect(peerAddress)
      .whenComplete { peer: Peer?, t: Throwable? ->
        if (t != null) {
          if (t is PeerAlreadyConnectedException) {
            log.info("Already connected to peer $peerAddress. Error: ${t.message}")
            reconnectWhenDisconnected(peer!!, peerAddress)
          } else {
            log.trace(
              "Failed to connect to peer {}, retrying after {} ms. Error: {}",
              peerAddress,
              p2pConfig.reconnectDelay,
              t.message,
            )
            SafeFuture
              .runAsync({ maintainPersistentConnection(peerAddress) }, delayedExecutor)
          }
        } else {
          log.info("Created persistent connection to {}", peerAddress)
          reconnectWhenDisconnected(peer!!, peerAddress)
        }
      }.thenApply {}

  private fun reconnectWhenDisconnected(
    peer: Peer,
    peerAddress: MultiaddrPeerAddress,
  ) {
    peer.subscribeDisconnect { _: Optional<DisconnectReason>, _: Boolean ->
      if (staticPeerMap.containsKey(peerAddress.id)) {
        SafeFuture.runAsync({ maintainPersistentConnection(peerAddress) }, delayedExecutor)
      }
    }
  }
}
