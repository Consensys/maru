/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.p2p.messages

import maru.p2p.Message
import maru.p2p.RpcMessageHandler
import maru.p2p.RpcMessageType
import tech.pegasys.teku.networking.eth2.rpc.core.ResponseCallback
import tech.pegasys.teku.networking.p2p.peer.Peer

class StatusHandler : RpcMessageHandler<Message<Status, RpcMessageType>, Message<Status, RpcMessageType>> {
  override fun handleIncomingMessage(
    peer: Peer,
    message: Message<Status, RpcMessageType>,
    callback: ResponseCallback<Message<Status, RpcMessageType>>,
  ) {
    println("Received status message from peer: ${peer.id} with payload: ${message.payload}")
    callback.respondAndCompleteSuccessfully(message) // TODO respond with real status message
    // update peer status
    // get our local status
    // respond with our local status
  }
}
