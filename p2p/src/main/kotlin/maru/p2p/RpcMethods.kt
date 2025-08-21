/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.p2p

import maru.database.BeaconChain
import maru.p2p.messages.BeaconBlocksByRangeHandler
import maru.p2p.messages.BeaconBlocksByRangeRequestMessageSerDe
import maru.p2p.messages.BeaconBlocksByRangeResponseMessageSerDe
import maru.p2p.messages.StatusHandler
import maru.p2p.messages.StatusMessageFactory
import maru.p2p.messages.StatusMessageSerDe
import maru.p2p.messages.StatusSerDe
import maru.serialization.rlp.RLPSerializers

class RpcMethods(
  statusMessageFactory: StatusMessageFactory,
  lineaRpcProtocolIdGenerator: LineaRpcProtocolIdGenerator,
  private val peerLookup: () -> PeerLookup,
  beaconChain: BeaconChain,
) {
  val statusMessageSerDe = StatusMessageSerDe(StatusSerDe())
  val statusRpcMethod =
    MaruRpcMethod(
      messageType = RpcMessageType.STATUS,
      rpcMessageHandler = StatusHandler(statusMessageFactory),
      requestMessageSerDe = statusMessageSerDe,
      responseMessageSerDe = statusMessageSerDe,
      peerLookup = peerLookup,
      protocolIdGenerator = lineaRpcProtocolIdGenerator,
      version = Version.V1,
      encoding = Encoding.RLP,
    )

  val beaconBlocksByRangeRequestMessageSerDe = BeaconBlocksByRangeRequestMessageSerDe()
  val beaconBlocksByRangeResponseMessageSerDe =
    BeaconBlocksByRangeResponseMessageSerDe(RLPSerializers.SealedBeaconBlockSerializer)

  val beaconBlocksByRangeRpcMethod =
    MaruRpcMethod(
      messageType = RpcMessageType.BEACON_BLOCKS_BY_RANGE,
      rpcMessageHandler = BeaconBlocksByRangeHandler(beaconChain),
      requestMessageSerDe = beaconBlocksByRangeRequestMessageSerDe,
      responseMessageSerDe = beaconBlocksByRangeResponseMessageSerDe,
      peerLookup = peerLookup,
      protocolIdGenerator = lineaRpcProtocolIdGenerator,
      version = Version.V1,
      encoding = Encoding.RLP_SNAPPY,
    )

  fun status() = statusRpcMethod

  fun beaconBlocksByRange() = beaconBlocksByRangeRpcMethod

  fun all(): List<MaruRpcMethod<*, *>> = listOf(statusRpcMethod, beaconBlocksByRangeRpcMethod)
}
