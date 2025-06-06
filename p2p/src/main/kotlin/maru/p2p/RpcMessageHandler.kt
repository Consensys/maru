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

import java.util.Optional
import okhttp3.Request
import tech.pegasys.teku.networking.eth2.rpc.core.ResponseCallback
import tech.pegasys.teku.networking.eth2.rpc.core.RpcException
import tech.pegasys.teku.networking.p2p.peer.Peer

/**
 * Interface for handling incoming RPC messages. Adapted from Teku LocalMessageHandler without use of the Teku Eth2Peer
 * which not applicable to Maru.
 *
 * @param TRequest The type of the request message.
 * @param TResponse The type of the response message.
 */
interface RpcMessageHandler<TRequest, TResponse> {
  fun handleIncomingMessage(
    peer: Peer,
    message: TRequest,
    callback: ResponseCallback<TResponse>,
  )

  fun validateRequest(request: Request): Optional<RpcException> = Optional.empty()
}
