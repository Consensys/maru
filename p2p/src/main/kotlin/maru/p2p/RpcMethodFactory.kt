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
import maru.p2p.messages.StatusHandler
import maru.p2p.messages.StatusMessageSerDe
import maru.p2p.messages.StatusSerDe
import maru.serialization.SerDe

class RpcMethodFactory(
  private val beaconChain: BeaconChain,
  chainId: UInt,
) {
  private val protocolIdGenerator = LineaRpcProtocolIdGenerator(chainId = chainId)

  data class RpcMethodRecord(
    val rpcMethod: MaruRpcMethod<*, *>,
    val serDe: SerDe<Message<*, RpcMessageType>>,
  )

  fun createRpcMethods(peerLookup: PeerLookup): Map<RpcMessageType, RpcMethodRecord> {
    val statusMessageSerDe = StatusMessageSerDe(StatusSerDe())
    val statusRpcMethod =
      MaruRpcMethod(
        messageType = RpcMessageType.STATUS,
        rpcMessageHandler = StatusHandler(beaconChain),
        requestMessageSerDe = statusMessageSerDe,
        responseMessageSerDe = statusMessageSerDe,
        peerLookup = peerLookup,
        protocolIdGenerator = protocolIdGenerator,
      )
    val statusRpcMethodRecord =
      RpcMethodRecord(
        rpcMethod = statusRpcMethod,
        serDe = statusMessageSerDe as SerDe<Message<*, RpcMessageType>>,
      )

    return mapOf(RpcMessageType.STATUS to statusRpcMethodRecord)
  }
}
