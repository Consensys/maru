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
import maru.database.BeaconChain
import maru.p2p.MaruPeer
import maru.p2p.Message
import maru.p2p.RpcMessageHandler
import maru.p2p.RpcMessageType
import tech.pegasys.teku.networking.eth2.rpc.core.ResponseCallback

class BeaconBlocksByRangeHandler(
  private val beaconChain: BeaconChain,
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

    // Fetch blocks from the beacon chain
    val blocks = getBlocksByRange(
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

  private fun getBlocksByRange(
    startBlockNumber: ULong,
    count: ULong,
  ): List<SealedBeaconBlock> {
    val blocks = mutableListOf<SealedBeaconBlock>()

    // Limit the number of blocks to prevent excessive memory usage
    val maxBlocks = minOf(count, MAX_BLOCKS_PER_REQUEST)

    for (i in 0UL until maxBlocks) {
      val blockNumber = startBlockNumber + i
      val block = beaconChain.getSealedBeaconBlock(blockNumber)

      // If we can't find a block, we've reached the end of the chain
      if (block == null) {
        break
      }

      blocks.add(block)
    }

    return blocks
  }

  companion object {
    // Maximum number of blocks to return in a single request
    // This matches the typical limit in Ethereum consensus specs
    const val MAX_BLOCKS_PER_REQUEST = 64UL
  }
}
