/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.p2p

import io.libp2p.core.ConnectionHandler
import io.libp2p.core.Host
import io.libp2p.core.PeerId
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.etc.types.seconds
import io.libp2p.pubsub.PubsubApiImpl
import io.libp2p.pubsub.gossip.Gossip
import io.libp2p.pubsub.gossip.GossipPeerScoreParams
import io.libp2p.pubsub.gossip.GossipScoreParams
import io.libp2p.pubsub.gossip.GossipTopicsScoreParams
import io.libp2p.pubsub.gossip.builders.GossipParamsBuilder
import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder
import io.libp2p.security.secio.SecIoSecureChannel
import io.libp2p.transport.tcp.TcpTransport
import java.util.Optional
import kotlin.random.Random
import maru.p2p.topics.SealedBlocksTopicHandler
import org.apache.tuweni.bytes.Bytes
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem
import pubsub.pb.Rpc
import tech.pegasys.teku.infrastructure.async.AsyncRunnerFactory
import tech.pegasys.teku.infrastructure.async.MetricTrackingExecutorFactory
import tech.pegasys.teku.infrastructure.unsigned.UInt64
import tech.pegasys.teku.networking.p2p.libp2p.LibP2PNetwork
import tech.pegasys.teku.networking.p2p.libp2p.LibP2PNodeId
import tech.pegasys.teku.networking.p2p.libp2p.PeerManager
import tech.pegasys.teku.networking.p2p.libp2p.gossip.GossipTopicHandlers
import tech.pegasys.teku.networking.p2p.libp2p.gossip.LibP2PGossipNetwork
import tech.pegasys.teku.networking.p2p.libp2p.gossip.PreparedPubsubMessage
import tech.pegasys.teku.networking.p2p.libp2p.rpc.RpcHandler
import tech.pegasys.teku.networking.p2p.network.P2PNetwork
import tech.pegasys.teku.networking.p2p.network.PeerHandler
import tech.pegasys.teku.networking.p2p.peer.Peer
import tech.pegasys.teku.networking.p2p.reputation.ReputationManager
import tech.pegasys.teku.networking.p2p.rpc.RpcMethod

data class TekuLibP2PNetwork(
  val p2PNetwork: P2PNetwork<Peer>,
  val host: Host,
)

class Libp2pNetworkFactory(
  private val domain: String,
) {
  fun build(
    privateKey: PrivKey,
    port: UInt,
    ipAddress: String,
    sealedBlocksTopicHandler: SealedBlocksTopicHandler,
    sealedBlocksTopicId: String,
    rpcMethods: List<RpcMethod<*, *, *>>,
    maruPeerManager: MaruPeerManager,
  ): TekuLibP2PNetwork {
    val ipv4Address = Multiaddr("/ip4/$ipAddress/tcp/$port")
    val gossipTopicHandlers = GossipTopicHandlers()

    gossipTopicHandlers.add(
      sealedBlocksTopicId,
      sealedBlocksTopicHandler,
    )

    val gossipParams = GossipParamsBuilder().heartbeatInterval(1.seconds).build()
    val gossipRouterBuilder =
      GossipRouterBuilder().apply {
        params = gossipParams
        scoreParams = GossipScoreParams(GossipPeerScoreParams(), GossipTopicsScoreParams())
        messageFactory = { getMessageFactory(it, gossipTopicHandlers) }
      }
    val gossipRouter = gossipRouterBuilder.build()
    val pubsubApiImpl = PubsubApiImpl(gossipRouter)
    val gossip = Gossip(gossipRouter, pubsubApiImpl)

    val metricsSystem = NoOpMetricsSystem()
    val publisherApi = gossip.createPublisher(privateKey, Random.nextLong())
    val gossipNetwork = LibP2PGossipNetwork(metricsSystem, gossip, publisherApi, gossipTopicHandlers)

    val peerId = PeerId.fromPubKey(privateKey.publicKey())
    val libP2PNodeId = LibP2PNodeId(peerId)

    val metricTrackingExecutorFactory = MetricTrackingExecutorFactory(metricsSystem)
    val asyncRunner = AsyncRunnerFactory.createDefault(metricTrackingExecutorFactory).create("maru", 2)
    val rpcHandlers =
      rpcMethods.map { rpcMethod ->
        RpcHandler(asyncRunner, rpcMethod)
      }

    val peerManager =
      PeerManager(
        metricsSystem,
        ReputationManager.NOOP,
        listOf<PeerHandler>(maruPeerManager),
        rpcHandlers,
        { _ -> 50.0 }, // TODO: I guess we need a scoring function here
      )

    val host =
      createHost(
        privateKey = privateKey,
        connectionHandlers = listOf(gossip, peerManager),
        gossip = gossip,
        rpcHandlers = rpcHandlers,
        ipv4Address = ipv4Address,
      )

    val advertisedAddresses = listOf(ipv4Address)

    val p2pNetwork =
      LibP2PNetwork(
        /* privKey = */ privateKey,
        /* nodeId = */ libP2PNodeId,
        /* host = */ host,
        /* peerManager = */ peerManager,
        /* advertisedAddresses = */ advertisedAddresses,
        /* gossipNetwork = */ gossipNetwork,
        /* listenPorts = */ listOf(port.toInt()),
      )
    return TekuLibP2PNetwork(p2pNetwork, host)
  }

  private fun getMessageFactory(
    msg: Rpc.Message,
    gossipTopicHandlers: GossipTopicHandlers,
  ): PreparedPubsubMessage {
    val arrivalTimestamp = Optional.empty<UInt64>()
    val topic = msg.getTopicIDs(0)
    val payload = Bytes.wrap(msg.data.toByteArray())

    val preparedMessage =
      gossipTopicHandlers
        .getHandlerForTopic(topic)
        .map { handler -> handler.prepareMessage(payload, arrivalTimestamp) }
        .orElse(
          MaruPreparedGossipMessage(
            origMessage = payload,
            arrTimestamp = arrivalTimestamp,
            domain = domain,
            topicId = topic,
          ),
        )

    return PreparedPubsubMessage(msg, preparedMessage)
  }

  private fun createHost(
    privateKey: PrivKey,
    connectionHandlers: List<ConnectionHandler>,
    gossip: Gossip,
    rpcHandlers: List<RpcHandler<*, *, *>>,
    ipv4Address: Multiaddr,
  ): Host =
    host {
      protocols {
        +gossip
        rpcHandlers.forEach { rpcHandler -> add(rpcHandler) }
      }
      network {
        listen(ipv4Address.toString())
      }
      transports {
        add(::TcpTransport)
      }
      identity {
        factory = { privateKey }
      }
      secureChannels {
        add { localKey, muxerProtocols -> SecIoSecureChannel(localKey, muxerProtocols) }
      }
      connectionHandlers {
        connectionHandlers.forEach { handler ->
          add(handler)
        }
      }
      muxers {
        add(StreamMuxerProtocol.Mplex)
      }
    }
}
