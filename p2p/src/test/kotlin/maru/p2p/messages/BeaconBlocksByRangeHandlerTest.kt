/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.p2p.messages

import maru.core.ext.DataGenerators
import maru.p2p.MaruPeer
import maru.p2p.Message
import maru.p2p.RpcMessageType
import maru.p2p.Version
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import tech.pegasys.teku.networking.eth2.rpc.core.ResponseCallback

class BeaconBlocksByRangeHandlerTest {
  private lateinit var blockProvider: BlockProvider
  private lateinit var handler: BeaconBlocksByRangeHandler
  private lateinit var peer: MaruPeer
  private lateinit var callback: ResponseCallback<Message<BeaconBlocksByRangeResponse, RpcMessageType>>

  @BeforeEach
  fun setup() {
    blockProvider = mock()
    handler = BeaconBlocksByRangeHandler(blockProvider)
    peer = mock()
    callback = mock()
  }

  @Test
  fun `handles request with no blocks available`() {
    val request = BeaconBlocksByRangeRequest(startBlockNumber = 100UL, count = 10UL)
    val message =
      Message(
        type = RpcMessageType.BEACON_BLOCKS_BY_RANGE,
        version = Version.V1,
        payload = request,
      )

    whenever(blockProvider.getBlocksByRange(100UL, 10UL)).thenReturn(emptyList())

    handler.handleIncomingMessage(peer, message, callback)

    val responseCaptor = argumentCaptor<Message<BeaconBlocksByRangeResponse, RpcMessageType>>()
    verify(callback).respondAndCompleteSuccessfully(responseCaptor.capture())

    val response = responseCaptor.firstValue
    assertThat(response.type).isEqualTo(RpcMessageType.BEACON_BLOCKS_BY_RANGE)
    assertThat(response.payload.blocks).isEmpty()
  }

  @Test
  fun `handles request with blocks available`() {
    val request = BeaconBlocksByRangeRequest(startBlockNumber = 100UL, count = 3UL)
    val message =
      Message(
        type = RpcMessageType.BEACON_BLOCKS_BY_RANGE,
        version = Version.V1,
        payload = request,
      )

    val blocks =
      listOf(
        DataGenerators.randomSealedBeaconBlock(number = 100UL),
        DataGenerators.randomSealedBeaconBlock(number = 101UL),
        DataGenerators.randomSealedBeaconBlock(number = 102UL),
      )

    whenever(blockProvider.getBlocksByRange(100UL, 3UL)).thenReturn(blocks)

    handler.handleIncomingMessage(peer, message, callback)

    val responseCaptor = argumentCaptor<Message<BeaconBlocksByRangeResponse, RpcMessageType>>()
    verify(callback).respondAndCompleteSuccessfully(responseCaptor.capture())

    val response = responseCaptor.firstValue
    assertThat(response.type).isEqualTo(RpcMessageType.BEACON_BLOCKS_BY_RANGE)
    assertThat(response.payload.blocks).hasSize(3)
    assertThat(response.payload.blocks).isEqualTo(blocks)
  }

  @Test
  fun `passes correct parameters to block provider`() {
    val request = BeaconBlocksByRangeRequest(startBlockNumber = 500UL, count = 64UL)
    val message =
      Message(
        type = RpcMessageType.BEACON_BLOCKS_BY_RANGE,
        version = Version.V1,
        payload = request,
      )

    whenever(blockProvider.getBlocksByRange(any(), any())).thenReturn(emptyList())

    handler.handleIncomingMessage(peer, message, callback)

    verify(blockProvider).getBlocksByRange(startBlockNumber = 500UL, count = 64UL)
  }

  @Test
  fun `handles large count request`() {
    val request = BeaconBlocksByRangeRequest(startBlockNumber = 0UL, count = 1000UL)
    val message =
      Message(
        type = RpcMessageType.BEACON_BLOCKS_BY_RANGE,
        version = Version.V1,
        payload = request,
      )

    // Block provider should handle limiting
    val limitedBlocks =
      (0UL until 64UL).map { i ->
        DataGenerators.randomSealedBeaconBlock(number = i)
      }

    whenever(blockProvider.getBlocksByRange(0UL, 1000UL)).thenReturn(limitedBlocks)

    handler.handleIncomingMessage(peer, message, callback)

    val responseCaptor = argumentCaptor<Message<BeaconBlocksByRangeResponse, RpcMessageType>>()
    verify(callback).respondAndCompleteSuccessfully(responseCaptor.capture())

    val response = responseCaptor.firstValue
    assertThat(response.payload.blocks).hasSize(64)
  }

  @Test
  fun `handles zero count request`() {
    val request = BeaconBlocksByRangeRequest(startBlockNumber = 100UL, count = 0UL)
    val message =
      Message(
        type = RpcMessageType.BEACON_BLOCKS_BY_RANGE,
        version = Version.V1,
        payload = request,
      )

    whenever(blockProvider.getBlocksByRange(100UL, 0UL)).thenReturn(emptyList())

    handler.handleIncomingMessage(peer, message, callback)

    val responseCaptor = argumentCaptor<Message<BeaconBlocksByRangeResponse, RpcMessageType>>()
    verify(callback).respondAndCompleteSuccessfully(responseCaptor.capture())

    val response = responseCaptor.firstValue
    assertThat(response.payload.blocks).isEmpty()
  }
}
