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

import org.apache.tuweni.bytes.Bytes
import tech.pegasys.teku.networking.p2p.libp2p.PeerManager
import tech.pegasys.teku.networking.p2p.rpc.RpcMethod
import tech.pegasys.teku.networking.p2p.rpc.RpcRequestHandler

class MaruRpcMethod : RpcMethod<MaruOutgoingRpcRequestHandler, Bytes, MaruRpcResponseHandler> {
  private lateinit var peerManager: PeerManager

  fun setPeerManager(peerManager: PeerManager) {
    this.peerManager = peerManager
  }

  override fun getIds(): MutableList<String> = mutableListOf("linea")

  override fun createIncomingRequestHandler(p0: String?): RpcRequestHandler {
    val maruRpcRequestHandler = MaruIncomingRpcRequestHandler(peerManager)
    println("createIncomingRequestHandler $p0 $maruRpcRequestHandler")
    return maruRpcRequestHandler
  }

  override fun createOutgoingRequestHandler(
    protocolId: String?,
    request: Bytes?,
    responseHandler: MaruRpcResponseHandler?,
  ): MaruOutgoingRpcRequestHandler {
    val maruRpcRequestHandler = MaruOutgoingRpcRequestHandler(responseHandler!!, peerManager)
    println("createOutgoingRequestHandler $protocolId $request $maruRpcRequestHandler")
    return maruRpcRequestHandler
  }

  override fun encodeRequest(p0: Bytes?): Bytes = p0!!
}
