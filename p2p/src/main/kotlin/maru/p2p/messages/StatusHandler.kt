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
import maru.p2p.RequestMessage
import maru.p2p.ResponseMessage
import maru.p2p.RpcMessageHandler
import maru.p2p.RpcMessageType
import org.apache.logging.log4j.LogManager
import tech.pegasys.teku.networking.eth2.rpc.core.ResponseCallback
import tech.pegasys.teku.networking.eth2.rpc.core.RpcException
import tech.pegasys.teku.networking.eth2.rpc.core.RpcResponseStatus
import tech.pegasys.teku.networking.p2p.peer.DisconnectReason

class StatusHandler(
  private val statusManager: StatusManager,
) : RpcMessageHandler<RequestMessage<Status, RpcMessageType>, ResponseMessage<Status, RpcMessageType>> {
  private val log = LogManager.getLogger(this.javaClass)

  override fun handleIncomingMessage(
    peer: MaruPeer,
    message: RequestMessage<Status, RpcMessageType>,
    callback: ResponseCallback<ResponseMessage<Status, RpcMessageType>>,
  ) {
    try {
      if (!statusManager.isValidForPeering(message.payload)) {
        log.warn(
          "Disconnecting peer=${peer.id} due to fork ID mismatch.",
        )
        peer.disconnectCleanly(DisconnectReason.IRRELEVANT_NETWORK)
        return
      }
      peer.updateStatus(message.payload)
      callback.respondAndCompleteSuccessfully(statusManager.createStatusResponseMessage())
    } catch (e: RpcException) {
      log.error("handling request failed with RpcException", e)
      callback.completeWithErrorResponse(e)
    } catch (th: Throwable) {
      log.error("handling request failed with unexpected error", th)
      callback.completeWithUnexpectedError(
        RpcException(
          RpcResponseStatus.SERVER_ERROR_CODE,
          "Handling request failed with unexpected error: " + th.message,
        ),
      )
    }
  }
}
