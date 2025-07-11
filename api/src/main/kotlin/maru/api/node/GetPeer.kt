/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.api.node

import io.javalin.http.Context
import io.javalin.http.Handler
import io.javalin.http.HttpStatus
import maru.api.HandlerException
import maru.api.NetworkDataProvider

data class GetPeerResponse(
  val data: PeerData,
)

class GetPeer(
  val networkDataProvider: NetworkDataProvider,
) : Handler {
  override fun handle(ctx: Context) {
    val peerId = ctx.pathParam(PEER_ID)
    val peer =
      try {
        networkDataProvider.getPeer(peerId)
      } catch (e: Exception) {
        if (e.message?.contains("invalid base58 encoded form") == true) {
          throw HandlerException(400, "Invalid peer ID: $peerId")
        }
        throw e
      }
    if (peer == null) {
      throw HandlerException(404, "Peer not found")
    }
    val peerData =
      PeerData(
        peerId = peer.nodeId,
        enr = peer.enr,
        lastSeenP2PAddress = peer.address,
        state = peer.status.toString().lowercase(),
        direction = peer.direction.toString().lowercase(),
      )
    ctx.status(HttpStatus.OK).json(GetPeerResponse(data = peerData))
  }

  companion object {
    const val PEER_ID = "peerId"
    const val ROUTE = "/eth/v1/node/peers/{$PEER_ID}"
  }
}
