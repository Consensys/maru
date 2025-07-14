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
import maru.database.BeaconChain
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
  private lateinit var beaconChain: BeaconChain
  private lateinit var handler: BeaconBlocksByRangeHandler
  private lateinit var peer: MaruPeer
  private lateinit var callback: ResponseCallback<Message<BeaconBlocksByRangeResponse, RpcMessageType>>

  @BeforeEach
  fun setup() {
    beaconChain = mock()
    handler = BeaconBlocksByRangeHandler(beaconChain)
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

    // Mock no blocks found
    whenever(beaconChain.getSealedBlocks(100UL, 10UL)).thenReturn(emptyList())

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

    // Mock blocks found
    whenever(beaconChain.getSealedBlocks(100UL, 3UL)).thenReturn(blocks)

    handler.handleIncomingMessage(peer, message, callback)

    val responseCaptor = argumentCaptor<Message<BeaconBlocksByRangeResponse, RpcMessageType>>()
    verify(callback).respondAndCompleteSuccessfully(responseCaptor.capture())

    val response = responseCaptor.firstValue
    assertThat(response.type).isEqualTo(RpcMessageType.BEACON_BLOCKS_BY_RANGE)
    assertThat(response.payload.blocks).hasSize(3)
    assertThat(response.payload.blocks).isEqualTo(blocks)
  }

  @Test
  fun `fetches blocks in sequence`() {
    val request = BeaconBlocksByRangeRequest(startBlockNumber = 500UL, count = 3UL)
    val message =
      Message(
        type = RpcMessageType.BEACON_BLOCKS_BY_RANGE,
        version = Version.V1,
        payload = request,
      )

    // Mock blocks found
    val block500 = DataGenerators.randomSealedBeaconBlock(number = 500UL)
    val block501 = DataGenerators.randomSealedBeaconBlock(number = 501UL)
    val blocks = listOf(block500, block501)
    
    whenever(beaconChain.getSealedBlocks(500UL, 3UL)).thenReturn(blocks)

    handler.handleIncomingMessage(peer, message, callback)

    // Verify the handler asked for the correct blocks
    verify(beaconChain).getSealedBlocks(500UL, 3UL)
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

    // Handler should limit to MAX_BLOCKS_PER_REQUEST (64)
    val limitedBlocks =
      (0UL until 64UL).map { i ->
        DataGenerators.randomSealedBeaconBlock(number = i)
      }

    // Mock blocks returned - handler should limit to 64
    whenever(beaconChain.getSealedBlocks(0UL, 64UL)).thenReturn(limitedBlocks)

    handler.handleIncomingMessage(peer, message, callback)

    val responseCaptor = argumentCaptor<Message<BeaconBlocksByRangeResponse, RpcMessageType>>()
    verify(callback).respondAndCompleteSuccessfully(responseCaptor.capture())

    val response = responseCaptor.firstValue
    assertThat(response.payload.blocks).hasSize(64)
    
    // Verify that the handler limited the request to 64 blocks
    verify(beaconChain).getSealedBlocks(0UL, 64UL)
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

    // With count = 0, no blocks should be fetched
    whenever(beaconChain.getSealedBlocks(100UL, 0UL)).thenReturn(emptyList())

    handler.handleIncomingMessage(peer, message, callback)

    val responseCaptor = argumentCaptor<Message<BeaconBlocksByRangeResponse, RpcMessageType>>()
    verify(callback).respondAndCompleteSuccessfully(responseCaptor.capture())

    val response = responseCaptor.firstValue
    assertThat(response.payload.blocks).isEmpty()
  }

  @Test
  fun `stops at gap in block sequence`() {
    val request = BeaconBlocksByRangeRequest(startBlockNumber = 100UL, count = 10UL)
    val message =
      Message(
        type = RpcMessageType.BEACON_BLOCKS_BY_RANGE,
        version = Version.V1,
        payload = request,
      )

    val block100 = DataGenerators.randomSealedBeaconBlock(number = 100UL)
    val block101 = DataGenerators.randomSealedBeaconBlock(number = 101UL)
    // Simulate gap at block 102
    val blocks = listOf(block100, block101)

    whenever(beaconChain.getSealedBlocks(100UL, 10UL)).thenReturn(blocks)

    handler.handleIncomingMessage(peer, message, callback)

    val responseCaptor = argumentCaptor<Message<BeaconBlocksByRangeResponse, RpcMessageType>>()
    verify(callback).respondAndCompleteSuccessfully(responseCaptor.capture())

    val response = responseCaptor.firstValue
    assertThat(response.payload.blocks).hasSize(2)
    assertThat(response.payload.blocks[0]).isEqualTo(block100)
    assertThat(response.payload.blocks[1]).isEqualTo(block101)
    // Should not include block103 due to gap
  }
}
