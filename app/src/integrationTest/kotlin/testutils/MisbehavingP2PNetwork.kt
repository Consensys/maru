/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package testutils

import java.lang.Thread.sleep
import maru.config.P2PConfig
import maru.consensus.ForkIdHashProvider
import maru.consensus.ForkIdHasher
import maru.core.SealedBeaconBlock
import maru.database.BeaconChain
import maru.database.P2PState
import maru.p2p.LineaRpcProtocolIdGenerator
import maru.p2p.MaruRpcMethod
import maru.p2p.P2PNetworkImpl
import maru.p2p.PeerLookup
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
) : P2PNetworkImpl(
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
      MisbehavingRpcMethods(
        statusMessageFactory,
        lineaRpcProtocolIdGenerator,
        peerLookup,
        beaconChain,
        beaconBlocksByRangeHandlerFactory,
      )
    },
  )

class MisbehavingRpcMethods(
  statusMessageFactory: StatusMessageFactory,
  lineaRpcProtocolIdGenerator: LineaRpcProtocolIdGenerator,
  peerLookup: () -> PeerLookup,
  beaconChain: BeaconChain,
  beaconBlocksByRangeHandlerFactory: (BeaconChain) -> BeaconBlocksByRangeHandler,
) : RpcMethods(
    statusMessageFactory,
    lineaRpcProtocolIdGenerator,
    peerLookup,
    beaconChain,
  ) {
  override val beaconBlocksByRangeRpcMethod =
    MaruRpcMethod(
      messageType = RpcMessageType.BEACON_BLOCKS_BY_RANGE,
      rpcMessageHandler = beaconBlocksByRangeHandlerFactory(beaconChain),
      requestMessageSerDe = beaconBlocksByRangeRequestMessageSerDe,
      responseMessageSerDe = beaconBlocksByRangeResponseMessageSerDe,
      peerLookup = peerLookup,
      protocolIdGenerator = lineaRpcProtocolIdGenerator,
      version = Version.V1,
    )
}

class FourEmptyResponsesBeaconBlocksByRangeHandler(
  val beaconChain: BeaconChain,
) : BeaconBlocksByRangeHandler(beaconChain) {
  var numCalls = 0

  override fun getBlocks(
    request: BeaconBlocksByRangeRequest,
    maxBlocks: ULong,
  ): List<SealedBeaconBlock> {
    if (numCalls < 4) {
      println("MisbehavingBeaconBlocksByRangeHandler: getBlocks called $maxBlocks")
      numCalls++
      return emptyList()
    } else {
      val blocks =
        beaconChain.getSealedBeaconBlocks(
          startBlockNumber = request.startBlockNumber,
          count = maxBlocks,
        )
      return blocks
    }
  }
}

class TimeOutResponsesBeaconBlocksByRangeHandler(
  val beaconChain: BeaconChain,
) : BeaconBlocksByRangeHandler(beaconChain) {
  override fun getBlocks(
    request: BeaconBlocksByRangeRequest,
    maxBlocks: ULong,
  ): List<SealedBeaconBlock> {
    sleep(6000) // longer than the timeout of 5 seconds
    val blocks =
      beaconChain.getSealedBeaconBlocks(
        startBlockNumber = request.startBlockNumber,
        count = maxBlocks,
      )
    return blocks
  }
}

class NormalResponsesBeaconBlocksByRangeHandler(
  private var beaconChain: BeaconChain,
) : BeaconBlocksByRangeHandler(beaconChain) {
  override fun getBlocks(
    request: BeaconBlocksByRangeRequest,
    maxBlocks: ULong,
  ): List<SealedBeaconBlock> {
    println("BeaconBlocksByRangeHandler: getBlocks called $maxBlocks")
    val blocks =
      beaconChain.getSealedBeaconBlocks(
        startBlockNumber = request.startBlockNumber,
        count = maxBlocks,
      )
    return blocks
  }
}
