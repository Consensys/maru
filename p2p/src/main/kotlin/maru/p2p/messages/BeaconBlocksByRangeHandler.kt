/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.p2p.messages

import maru.core.SealedBeaconBlock
import maru.p2p.MaruPeer
import maru.p2p.Message
import maru.p2p.RpcMessageHandler
import maru.p2p.RpcMessageType
import tech.pegasys.teku.networking.eth2.rpc.core.ResponseCallback

class BeaconBlocksByRangeHandler(
  private val blockProvider: BlockProvider,
) : RpcMessageHandler<
    Message<BeaconBlocksByRangeRequest, RpcMessageType>,
    Message<BeaconBlocksByRangeResponse, RpcMessageType>,
  > {
  override fun handleIncomingMessage(
    peer: MaruPeer,
    message: Message<BeaconBlocksByRangeRequest, RpcMessageType>,
    callback: ResponseCallback<Message<BeaconBlocksByRangeResponse, RpcMessageType>>,
  ) {
    val request = message.payload

    // Fetch blocks from the block provider
    val blocks =
      blockProvider.getBlocksByRange(
        startBlockNumber = request.startBlockNumber,
        count = request.count,
      )

    val response = BeaconBlocksByRangeResponse(blocks = blocks)
    val responseMessage =
      Message(
        type = RpcMessageType.BEACON_BLOCKS_BY_RANGE,
        payload = response,
      )

    callback.respondAndCompleteSuccessfully(responseMessage)
  }
}

/**
 * Interface for providing blocks by range
 */
interface BlockProvider {
  fun getBlocksByRange(
    startBlockNumber: ULong,
    count: ULong,
  ): List<SealedBeaconBlock>
}
