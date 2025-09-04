/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package testutils

import maru.config.P2PConfig
import maru.consensus.ForkIdHashProvider
import maru.consensus.ForkIdHasher
import maru.core.SealedBeaconBlock
import maru.database.BeaconChain
import maru.database.P2PState
import maru.p2p.Encoding
import maru.p2p.MaruRpcMethod
import maru.p2p.P2PNetworkImpl
import maru.p2p.RpcMessageType
import maru.p2p.RpcMethods
import maru.p2p.Version
import maru.p2p.messages.BeaconBlocksByRangeHandler
import maru.p2p.messages.BeaconBlocksByRangeRequest
import maru.p2p.messages.StatusMessageFactory
import maru.serialization.SerDe
import net.consensys.linea.metrics.MetricsFacade
import org.hyperledger.besu.plugin.services.MetricsSystem as BesuMetricsSystem

class MisbehavingP2PNetwork(
  privateKeyBytes: ByteArray,
  p2pConfig: P2PConfig,
  chainId: UInt,
  serDe: SerDe<SealedBeaconBlock>,
  metricsFacade: MetricsFacade,
  metricsSystem: BesuMetricsSystem,
  smf: StatusMessageFactory,
  chain: BeaconChain,
  forkIdHashProvider: ForkIdHashProvider,
  forkIdHasher: ForkIdHasher,
  isBlockImportEnabledProvider: () -> Boolean,
  p2pState: P2PState,
  beaconBlocksByRangeHandlerFactory: (BeaconChain) -> BeaconBlocksByRangeHandler,
) {
  val p2pNetwork: P2PNetworkImpl =
    P2PNetworkImpl(
      privateKeyBytes = privateKeyBytes,
      p2pConfig = p2pConfig,
      chainId = chainId,
      serDe = serDe,
      metricsFacade = metricsFacade,
      metricsSystem = metricsSystem,
      statusMessageFactory = smf,
      beaconChain = chain,
      forkIdHashProvider = forkIdHashProvider,
      forkIdHasher = forkIdHasher,
      isBlockImportEnabledProvider = isBlockImportEnabledProvider,
      p2PState = p2pState,
      rpcMethodsFactory = { statusMessageFactory, lineaRpcProtocolIdGenerator, peerLookup, beaconChain ->
        // Create custom RpcMethods with misbehaving handler
        object : RpcMethods(statusMessageFactory, lineaRpcProtocolIdGenerator, peerLookup, beaconChain) {
          override val beaconBlocksByRangeRpcMethod =
            MaruRpcMethod(
              messageType = RpcMessageType.BEACON_BLOCKS_BY_RANGE,
              rpcMessageHandler = beaconBlocksByRangeHandlerFactory(beaconChain),
              requestMessageSerDe = beaconBlocksByRangeRequestMessageSerDe,
              responseMessageSerDe = beaconBlocksByRangeResponseMessageSerDe,
              peerLookup = peerLookup,
              protocolIdGenerator = lineaRpcProtocolIdGenerator,
              version = Version.V1,
              encoding = Encoding.RLP_SNAPPY,
            )

          override fun beaconBlocksByRange() = beaconBlocksByRangeRpcMethod

          override fun all(): List<MaruRpcMethod<*, *>> = listOf(statusRpcMethod, beaconBlocksByRangeRpcMethod)
        }
      },
    )

  // Delegate all methods to the underlying P2PNetworkImpl
  fun start() = p2pNetwork.start()

  fun stop() = p2pNetwork.stop()

  fun close() = p2pNetwork.close()

  fun broadcastMessage(message: maru.p2p.Message<*, maru.p2p.GossipMessageType>) = p2pNetwork.broadcastMessage(message)

  fun subscribeToBlocks(subscriber: maru.p2p.SealedBeaconBlockHandler<maru.p2p.ValidationResult>) =
    p2pNetwork.subscribeToBlocks(subscriber)

  fun unsubscribeFromBlocks(subscriptionId: Int) = p2pNetwork.unsubscribeFromBlocks(subscriptionId)

  val port get() = p2pNetwork.port
  val nodeId get() = p2pNetwork.nodeId
  val nodeAddresses get() = p2pNetwork.nodeAddresses
  val discoveryAddresses get() = p2pNetwork.discoveryAddresses
  val localNodeRecord get() = p2pNetwork.localNodeRecord
  val enr get() = p2pNetwork.enr
  val peerCount get() = p2pNetwork.peerCount

  fun getPeers() = p2pNetwork.getPeers()

  fun getPeer(peerId: String) = p2pNetwork.getPeer(peerId)

  fun getPeerLookup() = p2pNetwork.getPeerLookup()

  fun dropPeer(peer: maru.p2p.PeerInfo) = p2pNetwork.dropPeer(peer)

  fun addPeer(address: String) = p2pNetwork.addPeer(address)

  fun handleForkTransition(forkSpec: maru.consensus.ForkSpec) = p2pNetwork.handleForkTransition(forkSpec)

  fun isStaticPeer(nodeId: tech.pegasys.teku.networking.p2p.peer.NodeId) = p2pNetwork.isStaticPeer(nodeId)
}

// Legacy handler implementations for backward compatibility
class FourEmptyResponsesBeaconBlocksByRangeHandler(
  beaconChain: BeaconChain,
) : BeaconBlocksByRangeHandler(beaconChain) {
  var numCalls = 0

  override fun getBlocks(
    request: maru.p2p.messages.BeaconBlocksByRangeRequest,
    maxBlocks: ULong,
  ): List<SealedBeaconBlock> {
    if (numCalls < 4) {
      numCalls++
      return emptyList()
    } else {
      return beaconChain.getSealedBeaconBlocks(
        startBlockNumber = request.startBlockNumber,
        count = maxBlocks,
      )
    }
  }
}

class TimeOutResponsesBeaconBlocksByRangeHandler(
  beaconChain: BeaconChain,
) : BeaconBlocksByRangeHandler(beaconChain) {
  override fun getBlocks(
    request: maru.p2p.messages.BeaconBlocksByRangeRequest,
    maxBlocks: ULong,
  ): List<SealedBeaconBlock> {
    Thread.sleep(6000) // longer than the timeout of 5 seconds
    return beaconChain.getSealedBeaconBlocks(
      startBlockNumber = request.startBlockNumber,
      count = maxBlocks,
    )
  }
}

class NormalResponsesBeaconBlocksByRangeHandler(
  beaconChain: BeaconChain,
) : BeaconBlocksByRangeHandler(beaconChain) {
  override fun getBlocks(
    request: maru.p2p.messages.BeaconBlocksByRangeRequest,
    maxBlocks: ULong,
  ): List<SealedBeaconBlock> =
    beaconChain.getSealedBeaconBlocks(
      startBlockNumber = request.startBlockNumber,
      count = maxBlocks,
    )
}
