/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.p2p.messages

import maru.p2p.MaruPeer
import maru.p2p.Message
import maru.p2p.RpcMessageHandler
import maru.p2p.RpcMessageType
import tech.pegasys.teku.networking.eth2.rpc.core.ResponseCallback

class LatestBeaconStateHandler :
  RpcMessageHandler<
    Message<LatestBeaconStateRequest, RpcMessageType>,
    Message<LatestBeaconStateResponse, RpcMessageType>,
  > {
  override fun handleIncomingMessage(
    peer: MaruPeer,
    message: Message<LatestBeaconStateRequest, RpcMessageType>,
    callback: ResponseCallback<Message<LatestBeaconStateResponse, RpcMessageType>>,
  ) {
    TODO("Not yet implemented")
  }
}
