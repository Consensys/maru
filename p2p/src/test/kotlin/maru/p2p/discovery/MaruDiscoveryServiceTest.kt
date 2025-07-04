/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.p2p.discovery

import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Optional
import maru.consensus.ForkId
import maru.consensus.ForkId.Companion.FORK_ID_FIELD_NAME
import org.apache.tuweni.bytes.Bytes
import org.assertj.core.api.Assertions.assertThat
import org.ethereum.beacon.discovery.schema.EnrField
import org.ethereum.beacon.discovery.schema.NodeRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class MaruDiscoveryServiceTest {
  private lateinit var service: MaruDiscoveryService
  private val dummyForkId =
    ForkId(
      chainId = 1.toUInt(),
      genesisRootHash =
        ByteArray(32) {
          0
        },
    )
  private val dummyForkId2 =
    ForkId(
      chainId = 2.toUInt(),
      genesisRootHash =
        ByteArray(32) {
          0
        },
    )
  private val dummyPrivKey = ByteArray(32) { 1 }
  private val dummyNodeId = Bytes.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 0)
  private val dummyAddr = Optional.of(InetSocketAddress(InetAddress.getByName("1.1.1.1"), 1234))

  @BeforeEach
  fun setUp() {
    val p2pConfig = mock(maru.config.P2P::class.java)
    `when`(p2pConfig.ipAddress).thenReturn("127.0.0.1")
    `when`(p2pConfig.discoveryPort).thenReturn(9000.toUInt())
    `when`(p2pConfig.port).thenReturn(9001.toUInt())
    `when`(p2pConfig.bootnodes).thenReturn(listOf())
    service = MaruDiscoveryService(dummyPrivKey, p2pConfig) { dummyForkId }
  }

  @Test
  fun `converts node record with valid forkId`() {
    val node = mock(NodeRecord::class.java)
    val pubKey = Bytes.of(9, 9, 9)
    `when`(node.get(EnrField.PKEY_SECP256K1)).thenReturn(pubKey)
    `when`(node.get(FORK_ID_FIELD_NAME)).thenReturn(Bytes.wrap(dummyForkId.bytes))
    `when`(node.nodeId).thenReturn(dummyNodeId)
    `when`(node.tcpAddress).thenReturn(dummyAddr)

    val peer =
      service.run {
        val method = this::class.java.getDeclaredMethod("convertNodeRecordToDiscoveryPeer", NodeRecord::class.java)
        method.isAccessible = true
        method.invoke(this, node) as MaruDiscoveryPeer
      }

    assertEquals(pubKey, peer.publicKey)
    assertEquals(dummyNodeId, peer.nodeId)
    assertEquals(dummyAddr.get(), peer.addr)
    assertTrue(peer.forkIdBytes.isPresent)
    assertEquals(Bytes.wrap(dummyForkId.bytes), Bytes.wrap(peer.forkIdBytes.get()))
  }

  @Test
  fun `returns empty forkId if forkId field is missing`() {
    val node = mock(NodeRecord::class.java)
    `when`(node.get(FORK_ID_FIELD_NAME)).thenReturn(null)
    `when`(node.get(EnrField.PKEY_SECP256K1)).thenReturn(null)
    `when`(node.nodeId).thenReturn(dummyNodeId)
    `when`(node.tcpAddress).thenReturn(dummyAddr)

    val peer =
      service.run {
        val method = this::class.java.getDeclaredMethod("convertNodeRecordToDiscoveryPeer", NodeRecord::class.java)
        method.isAccessible = true
        method.invoke(this, node) as MaruDiscoveryPeer
      }

    assertTrue(peer.forkIdBytes.isEmpty)
  }

  @Test
  fun `returns empty forkId if forkId field is not Bytes`() {
    val node = mock(NodeRecord::class.java)
    `when`(node.get(FORK_ID_FIELD_NAME)).thenReturn("notBytes")
    `when`(node.get(EnrField.PKEY_SECP256K1)).thenReturn(null)
    `when`(node.nodeId).thenReturn(dummyNodeId)
    `when`(node.tcpAddress).thenReturn(dummyAddr)

    val peer =
      service.run {
        val method = this::class.java.getDeclaredMethod("convertNodeRecordToDiscoveryPeer", NodeRecord::class.java)
        method.isAccessible = true
        method.invoke(this, node) as MaruDiscoveryPeer
      }

    assertTrue(peer.forkIdBytes.isEmpty)
  }

  @Test
  fun `updateForkId updates local `() {
    val localNodeRecordBefore = service.getLocalNodeRecord()

    service.updateForkId(dummyForkId2)

    val localNodeRecordAfter = service.getLocalNodeRecord()
    val actual = localNodeRecordAfter.get(FORK_ID_FIELD_NAME)
    assertThat(actual).isNotEqualTo(localNodeRecordBefore.get(FORK_ID_FIELD_NAME))
    assertThat(actual)
      .isEqualTo(Bytes.wrap(dummyForkId2.bytes))
  }
}
