/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.p2p

import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufUtil
import maru.serialization.Serializer
import tech.pegasys.teku.networking.p2p.peer.NodeId
import tech.pegasys.teku.networking.p2p.rpc.RpcRequestHandler
import tech.pegasys.teku.networking.p2p.rpc.RpcStream

class MaruIncomingRpcRequestHandler<TRequest : Message<*, RpcMessageType>, TResponse : Message<*, RpcMessageType>>(
  private val rpcMessageHandler: RpcMessageHandler<TRequest, TResponse>,
  private val requestMessageSerializer: Serializer<TRequest>,
  private val responseMessageSerializer: Serializer<TResponse>,
  private val peerLookup: PeerLookup,
) : RpcRequestHandler {
  override fun active(
    nodeId: NodeId,
    rpcStream: RpcStream,
  ) {
  }

  override fun processData(
    nodeId: NodeId,
    rpcStream: RpcStream,
    byteBuffer: ByteBuf,
  ) {
    val bytes = ByteBufUtil.getBytes(byteBuffer)
    val peer = peerLookup.getPeer(nodeId)

    // TODO handle unchecked cast
    val message = requestMessageSerializer.deserialize(bytes) as TRequest

    rpcMessageHandler.handleIncomingMessage(
      peer = peer,
      message = message,
      callback =
        MaruRpcResponseCallback(
          rpcStream = rpcStream,
          messageSerializer = responseMessageSerializer,
        ),
    )
  }

  override fun readComplete(
    nodeId: NodeId,
    rpcStream: RpcStream,
  ) {
  }

  override fun closed(
    nodeId: NodeId,
    rpcStream: RpcStream,
  ) {
  }
}
