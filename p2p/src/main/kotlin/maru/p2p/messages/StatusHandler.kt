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
package maru.p2p.messages

import maru.p2p.Message
import maru.p2p.RpcMessageHandler
import tech.pegasys.teku.networking.eth2.rpc.core.ResponseCallback
import tech.pegasys.teku.networking.p2p.peer.Peer

class StatusHandler : RpcMessageHandler<Message<Status>, Message<Status>> {
  override fun handleIncomingMessage(
    peer: Peer,
    message: Message<Status>,
    callback: ResponseCallback<Message<Status>>,
  ) {
    println("Received status message from peer: ${peer.id} with payload: ${message.payload}")
    callback.respondAndCompleteSuccessfully(message) // TODO respond with real status message
    // update peer status
    // get our local status
    // respond with our local status
  }
}
