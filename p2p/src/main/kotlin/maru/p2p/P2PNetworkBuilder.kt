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

// import org.hyperledger.besu.metrics.prometheus.PrometheusMetricsSystem
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.Host
import io.libp2p.core.PeerId
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.dsl.BuilderJ
import io.libp2p.core.dsl.hostJ
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.mux.StreamMuxer
import io.libp2p.core.pubsub.ValidationResult
import io.libp2p.etc.types.millis
import io.libp2p.pubsub.PubsubApiImpl
import io.libp2p.pubsub.gossip.Gossip
import io.libp2p.pubsub.gossip.GossipPeerScoreParams
import io.libp2p.pubsub.gossip.GossipScoreParams
import io.libp2p.pubsub.gossip.GossipTopicsScoreParams
import io.libp2p.pubsub.gossip.builders.GossipParamsBuilder
import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder
import io.libp2p.security.secio.SecIoSecureChannel
import io.libp2p.transport.ConnectionUpgrader
import io.libp2p.transport.tcp.TcpTransport
import io.netty.buffer.ByteBuf
import java.util.Optional
import kotlin.random.Random
import org.apache.tuweni.bytes.Bytes
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem
import pubsub.pb.Rpc
import tech.pegasys.teku.infrastructure.async.AsyncRunnerFactory
import tech.pegasys.teku.infrastructure.async.MetricTrackingExecutorFactory
import tech.pegasys.teku.infrastructure.async.SafeFuture
import tech.pegasys.teku.infrastructure.unsigned.UInt64
import tech.pegasys.teku.networking.p2p.gossip.PreparedGossipMessage
import tech.pegasys.teku.networking.p2p.gossip.TopicHandler
import tech.pegasys.teku.networking.p2p.libp2p.LibP2PNetwork
import tech.pegasys.teku.networking.p2p.libp2p.LibP2PNodeId
import tech.pegasys.teku.networking.p2p.libp2p.PeerManager
import tech.pegasys.teku.networking.p2p.libp2p.gossip.GossipTopicHandlers
import tech.pegasys.teku.networking.p2p.libp2p.gossip.LibP2PGossipNetwork
import tech.pegasys.teku.networking.p2p.libp2p.gossip.PreparedPubsubMessage
import tech.pegasys.teku.networking.p2p.libp2p.rpc.RpcHandler
import tech.pegasys.teku.networking.p2p.network.P2PNetwork
import tech.pegasys.teku.networking.p2p.network.PeerHandler
import tech.pegasys.teku.networking.p2p.peer.NodeId
import tech.pegasys.teku.networking.p2p.peer.Peer
import tech.pegasys.teku.networking.p2p.reputation.ReputationManager
import tech.pegasys.teku.networking.p2p.rpc.RpcMethod
import tech.pegasys.teku.networking.p2p.rpc.RpcRequestHandler
import tech.pegasys.teku.networking.p2p.rpc.RpcResponseHandler
import tech.pegasys.teku.networking.p2p.rpc.RpcStream

private const val ORIGINAL_MESSAGE = "68656c6c6f"

open class P2PNetworkBuilder {
  private var privateKey: PrivKey? = null
  private var ipv4Address: Multiaddr? = null
  private var ipv6Address: Multiaddr? = null

  fun build(): P2PNetwork<Peer> {
    // check everything that is needed is set
    if (privateKey == null) {
      throw IllegalArgumentException("Private key is required")
    }

    if (ipv4Address == null && ipv6Address == null) {
      throw IllegalArgumentException("At least one IP address is required")
    }

    return setupP2PNetwork()
  }

  fun privateKey(privKey: PrivKey): P2PNetworkBuilder {
    this.privateKey = privKey
    return this
  }

  fun ipv4Network(ipv4Address: Multiaddr): P2PNetworkBuilder {
    this.ipv4Address = ipv4Address
    return this
  }

  fun ipv6Network(ipv6Address: Multiaddr): P2PNetworkBuilder {
    this.ipv6Address = ipv6Address
    return this
  }

  private val gossipTopicHandlers: GossipTopicHandlers
    get() {
      val gossipTopicHandlers = GossipTopicHandlers()
      return gossipTopicHandlers
    }

  private fun setupP2PNetwork(): P2PNetwork<Peer> {
    val gossipTopicsScoreParams = GossipTopicsScoreParams()
    val gossipPeerScoreParams = GossipPeerScoreParams()
    val gossipScoreParams =
      GossipScoreParams(
        gossipPeerScoreParams,
        gossipTopicsScoreParams,
      )
    val gossipParams = GossipParamsBuilder().heartbeatInterval(100.millis).build()

    gossipTopicHandlers.add("topic", TestTopicHandler())
    val gossipRouterBuilder = GossipRouterBuilder()
    gossipRouterBuilder.params = gossipParams
    gossipRouterBuilder.scoreParams = gossipScoreParams
    gossipRouterBuilder.messageFactory = { getMessageFactory(it) }
    val gossipRouter = gossipRouterBuilder.build()

    val pubsubApiImpl = PubsubApiImpl(gossipRouter)
    val gossip = Gossip(gossipRouter, pubsubApiImpl)

    val metricsSystem = NoOpMetricsSystem() // TODO: add if it is needed

    val publisherApi = gossip.createPublisher(privateKey, Random.nextLong())

    val gossipNetwork =
      LibP2PGossipNetwork(
        metricsSystem,
        gossip,
        publisherApi,
        gossipTopicHandlers,
      )

    val peerId = privateKey?.let { PeerId.fromPubKey(it.publicKey()) }
    val libP2PNodeId = LibP2PNodeId(peerId)

    val metricTrackingExecutorFactory = MetricTrackingExecutorFactory(metricsSystem)
    val asyncRunner = AsyncRunnerFactory.createDefault(metricTrackingExecutorFactory).create("maru", 2)
    val maruRpcMethod = rpcMethod // TODO: MaruRpcMethod needs to be implemented
    val rpcHandler = RpcHandler(asyncRunner, maruRpcMethod)

    val peerManager =
      PeerManager(
        metricsSystem,
        ReputationManager.NOOP,
        mutableListOf<PeerHandler>(MaruPeerHandler()),
        mutableListOf(rpcHandler),
        { _ -> 50.0 },
      ) // TODO need to implement the scoring function

    val host = createHost(privateKey!!, listOf(gossip, peerManager), gossip)
    // Add additional connection handlers and protocol bindings as needed

    val advertisedAddresses = mutableListOf<Multiaddr>()
    ipv4Address?.let { advertisedAddresses.add(it) }
    ipv6Address?.let { advertisedAddresses.add(it) }

    val libP2PNetwork =
      LibP2PNetwork(
        privateKey,
        libP2PNodeId,
        host,
        peerManager,
        advertisedAddresses,
        gossipNetwork,
        mutableListOf(1), // TODO: this does not seem to be used at all
      )

    return libP2PNetwork
  }

  private fun getMessageFactory(msg: Rpc.Message): PreparedPubsubMessage {
    //      com.google.common.base.Preconditions.checkArgument(
    //        msg.topicIDsCount == 1,
    //        "Unexpected number of topics for a single message: " + msg.topicIDsCount
    //      )
    //      val arrivalTimestamp: Optional<UInt64> = if (recordArrivalTime) {
    //      Optional.of<UInt64>(timeProvider.getTimeInMillis())
    //    } else {
    //      Optional.empty<UInt64>()
    //    }
    val arrivalTimestamp = Optional.empty<UInt64>()
    val topic = msg.getTopicIDs(0)
    val payload = Bytes.wrap(msg.data.toByteArray())

    val preparedMessage: PreparedGossipMessage =
      gossipTopicHandlers
        .getHandlerForTopic(topic)
        .map { handler: TopicHandler ->
          handler.prepareMessage(
            payload,
            arrivalTimestamp,
          )
        }.orElse(
          // TODO: this is not the correct behavior, we should not be creating a message if there is no handler
          MaruPreparedGossipMessage(payload, arrivalTimestamp),
        )
    return PreparedPubsubMessage(msg, preparedMessage)
  }

  private fun createHost(
    privKey: PrivKey,
    connectionHandlers: List<ConnectionHandler>,
    gossip: Gossip,
  ): Host {
    // networkInterfaces is a list of IP addresses. At least one IP address (IPv4) is required.
    // If two IP addresses are provided, one must be IPv4 and the other must be IPv6.
    val listenAddrs = mutableListOf<String>()

    if (ipv4Address != null) {
      listenAddrs += ipv4Address.toString()
    }
    if (ipv6Address != null) {
      listenAddrs += ipv6Address.toString()
    }
    if (listenAddrs.isEmpty()) {
      throw IllegalArgumentException("Need at least one IP address to listen on")
    }

    val host =
      hostJ(io.libp2p.core.dsl.Builder.Defaults.Standard) { b: BuilderJ ->
        // uses the standard configuration
        b.identity.factory = { privKey }
        b.transports.add { upgrader: ConnectionUpgrader -> TcpTransport(upgrader) }
        listenAddrs.forEach { b.network.listen(it) }
        b.secureChannels.add { localKey: PrivKey, muxerProtocols: List<StreamMuxer> ->
          SecIoSecureChannel(
            localKey,
            muxerProtocols,
          )
        }
        connectionHandlers.forEach { b.connectionHandlers.add(it) }
        b.protocols.add(gossip) // TODO: add the rest of the protocols
      }
    return host
  }

  class MaruPeerHandler : PeerHandler {
    override fun onConnect(peer: Peer?) {
      // TODO("Not yet implemented1")
    }

    override fun onDisconnect(peer: Peer?) {
      // TODO("Not yet implemented2")
    }
  }

  open class MaruRpcMethod : RpcMethod<MaruRpcRequestHandler, Bytes, RpcResponseHandler<Bytes>> {
    override fun getIds(): MutableList<String> = mutableListOf("/mplex/6.7.0")

    override fun createIncomingRequestHandler(p0: String?): RpcRequestHandler = MaruRpcRequestHandler()

    override fun createOutgoingRequestHandler(
      p0: String?,
      p1: Bytes?,
      p2: RpcResponseHandler<Bytes>?,
    ): MaruRpcRequestHandler = MaruRpcRequestHandler()

    override fun encodeRequest(p0: Bytes?): Bytes = p0!!
  }

  open class MaruRpcRequestHandler : RpcRequestHandler {
    override fun active(
      p0: NodeId?,
      p1: RpcStream?,
    ) {
      TODO("Not yet implemented7")
    }

    override fun processData(
      p0: NodeId?,
      p1: RpcStream?,
      p2: ByteBuf?,
    ) {
      TODO("Not yet implemented8")
    }

    override fun readComplete(
      p0: NodeId?,
      p1: RpcStream?,
    ) {
      TODO("Not yet implemented9")
    }

    override fun closed(
      p0: NodeId?,
      p1: RpcStream?,
    ) {
      TODO("Not yet implemented10")
    }
  }

  class TestTopicHandler : TopicHandler {
    override fun prepareMessage(
      payload: Bytes?,
      arrivalTimestamp: Optional<UInt64>?,
    ): PreparedGossipMessage = MaruPreparedGossipMessage(payload!!, arrivalTimestamp!!)

    override fun handleMessage(message: PreparedGossipMessage?): SafeFuture<ValidationResult> {
      var data: Bytes?
      message.let {
        data = message!!.originalMessage
      }
      return if (data!!.equals(ORIGINAL_MESSAGE)) {
        SafeFuture.completedFuture(ValidationResult.Valid)
      } else {
        SafeFuture.completedFuture(ValidationResult.Invalid)
      }
    }

    override fun getMaxMessageSize(): Int = 424242
  }

  class MaruPreparedGossipMessage(
    private val origMessage: Bytes,
    private val arrTimestamp: Optional<UInt64>,
  ) : PreparedGossipMessage {
    override fun getMessageId(): Bytes = Bytes.of(42)

    override fun getDecodedMessage(): PreparedGossipMessage.DecodedMessageResult =
      PreparedGossipMessage.DecodedMessageResult.successful(origMessage)

    override fun getOriginalMessage(): Bytes = origMessage

    override fun getArrivalTimestamp(): Optional<UInt64> = arrTimestamp
  }

  companion object {
    var rpcMethod = MaruRpcMethod()
  }
}
