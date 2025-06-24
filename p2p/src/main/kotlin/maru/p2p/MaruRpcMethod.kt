/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.p2p

import maru.serialization.SerDe
import org.apache.tuweni.bytes.Bytes
import tech.pegasys.teku.networking.p2p.rpc.RpcMethod
import tech.pegasys.teku.networking.p2p.rpc.RpcRequestHandler

class MaruRpcMethod<TRequest : Message<*, RpcMessageType>, TResponse : Message<*, RpcMessageType>>(
  private val messageType: RpcMessageType,
  private val rpcMessageHandler: RpcMessageHandler<TRequest, TResponse>,
  private val requestMessageSerDe: SerDe<TRequest>,
  private val responseMessageSerDe: SerDe<TResponse>,
  private val peerLookup: PeerLookup,
  private val version: Version,
  protocolIdGenerator: MessageIdGenerator,
) : RpcMethod<MaruOutgoingRpcRequestHandler, TRequest, MaruRpcResponseHandler> {
  private val protocolId = protocolIdGenerator.id(messageType.name, version)

  override fun getIds(): MutableList<String> = mutableListOf(protocolId)

  override fun createIncomingRequestHandler(protocolId: String): RpcRequestHandler =
    MaruIncomingRpcRequestHandler<TRequest, TResponse>(
      rpcMessageHandler = rpcMessageHandler,
      requestMessageSerDe = requestMessageSerDe,
      responseMessageSerDe = responseMessageSerDe,
      peerLookup = peerLookup,
    )

  override fun createOutgoingRequestHandler(
    protocolId: String,
    request: TRequest,
    responseHandler: MaruRpcResponseHandler,
  ): MaruOutgoingRpcRequestHandler = MaruOutgoingRpcRequestHandler(responseHandler)

  override fun encodeRequest(request: TRequest): Bytes = Bytes.wrap(requestMessageSerDe.serialize(request))

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is MaruRpcMethod<*, *>) return false

    if (messageType != other.messageType) return false
    if (protocolId != other.protocolId) return false
    if (version != other.version) return false

    return true
  }

  override fun hashCode(): Int {
    var result = messageType.hashCode()
    result = 31 * result + protocolId.hashCode()
    result = 31 * result + version.hashCode()
    return result
  }
}
