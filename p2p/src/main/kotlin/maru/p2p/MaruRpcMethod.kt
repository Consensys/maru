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

import maru.serialization.Serializer
import org.apache.tuweni.bytes.Bytes
import tech.pegasys.teku.networking.p2p.rpc.RpcMethod
import tech.pegasys.teku.networking.p2p.rpc.RpcRequestHandler

class MaruRpcMethod<TRequest : Message<*, RpcMessageType>, TResponse : Message<*, RpcMessageType>>(
  private val messageType: RpcMessageType,
  private val rpcMessageHandler: RpcMessageHandler<TRequest, TResponse>,
  private val requestMessageSerializer: Serializer<TRequest>,
  private val responseMessageSerializer: Serializer<TResponse>,
  private val peerLookup: PeerLookup,
  protocolIdGenerator: MessageIdGenerator,
) : RpcMethod<MaruOutgoingRpcRequestHandler, Bytes, MaruRpcResponseHandler> {
  private val protocolId = protocolIdGenerator.id(messageType, version = Version.V1)

  override fun getIds(): MutableList<String> = mutableListOf(protocolId)

  override fun createIncomingRequestHandler(protocolId: String): RpcRequestHandler =
    MaruIncomingRpcRequestHandler<TRequest, TResponse>(
      rpcMessageHandler = rpcMessageHandler,
      requestMessageSerializer = requestMessageSerializer,
      responseMessageSerializer = responseMessageSerializer,
      peerLookup = peerLookup,
    )

  override fun createOutgoingRequestHandler(
    protocolId: String,
    request: Bytes,
    responseHandler: MaruRpcResponseHandler,
  ): MaruOutgoingRpcRequestHandler = MaruOutgoingRpcRequestHandler(responseHandler)

  override fun encodeRequest(bytes: Bytes): Bytes = bytes

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is MaruRpcMethod<*, *>) return false

    if (messageType != other.messageType) return false
    if (protocolId != other.protocolId) return false

    return true
  }

  override fun hashCode(): Int {
    var result = messageType.hashCode()
    result = 31 * result + protocolId.hashCode()
    return result
  }
}
