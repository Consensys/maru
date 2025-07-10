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
import java.util.concurrent.TimeUnit
import maru.config.P2P
import maru.consensus.ForkId
import maru.p2p.discovery.MaruDiscoveryService.Companion.FORK_ID_FIELD_NAME
import maru.p2p.getBootnodeEnrString
import org.apache.tuweni.bytes.Bytes
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility
import org.ethereum.beacon.discovery.schema.EnrField
import org.ethereum.beacon.discovery.schema.NodeRecord
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.whenever

class MaruDiscoveryServiceTest {
  companion object {
    private const val IPV4 = "127.0.0.1"

    private const val PORT1 = 9234u
    private const val PORT2 = 9235u
    private const val PORT3 = 9236u
    private const val PORT4 = 9237u
    private const val PORT5 = 9238u
    private const val PORT6 = 9239u

    private const val PRIVATE_KEY1: String =
      "0x12c0b113e2b0c37388e2b484112e13f05c92c4471e3ee1dfaa368fa5045325b2"
    private const val PRIVATE_KEY2: String =
      "0xf3d2fffa99dc8906823866d96316492ebf7a8478713a89a58b7385af85b088a1"
    private const val PRIVATE_KEY3: String =
      "0x4437acb8e84bc346f7640f239da84abe99bc6f97b7855f204e34688d2977fd57"

    private val key1 = Bytes.fromHexString(PRIVATE_KEY1).toArray()
    private val key2 = Bytes.fromHexString(PRIVATE_KEY2).toArray()
    private val key3 = Bytes.fromHexString(PRIVATE_KEY3).toArray()

    private val forkIdProvider = {
      ForkId(
        chainId = 1L.toUInt(),
        genesisRootHash = ByteArray(32),
      )
    }
  }

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
    val p2pConfig = mock(P2P::class.java)
    whenever(p2pConfig.ipAddress).thenReturn("127.0.0.1")
    whenever(p2pConfig.discoveryPort).thenReturn(9000.toUInt())
    whenever(p2pConfig.port).thenReturn(9001.toUInt())
    whenever(p2pConfig.bootnodes).thenReturn(listOf())
    service = MaruDiscoveryService(dummyPrivKey, p2pConfig, { dummyForkId }, NoOpMetricsSystem())
  }

  @Test
  fun `converts node record with valid forkId`() {
    val node = mock(NodeRecord::class.java)
    val pubKey = Bytes.of(9, 9, 9)
    whenever(node.get(EnrField.PKEY_SECP256K1)).thenReturn(pubKey)
    whenever(node.get(FORK_ID_FIELD_NAME)).thenReturn(Bytes.wrap(dummyForkId.bytes))
    whenever(node.nodeId).thenReturn(dummyNodeId)
    whenever(node.tcpAddress).thenReturn(dummyAddr)

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
    whenever(node.get(FORK_ID_FIELD_NAME)).thenReturn(null)
    whenever(node.get(EnrField.PKEY_SECP256K1)).thenReturn(null)
    whenever(node.nodeId).thenReturn(dummyNodeId)
    whenever(node.tcpAddress).thenReturn(dummyAddr)

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
    whenever(node.get(FORK_ID_FIELD_NAME)).thenReturn("notBytes")
    whenever(node.get(EnrField.PKEY_SECP256K1)).thenReturn(null)
    whenever(node.nodeId).thenReturn(dummyNodeId)
    whenever(node.tcpAddress).thenReturn(dummyAddr)

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
    val localNodeRecordBefore = service.localNodeRecord

    service.updateForkId(dummyForkId2)

    val localNodeRecordAfter = service.localNodeRecord
    val actual = localNodeRecordAfter.get(FORK_ID_FIELD_NAME)
    assertThat(actual).isNotEqualTo(localNodeRecordBefore.get(FORK_ID_FIELD_NAME))
    assertThat(actual)
      .isEqualTo(Bytes.wrap(dummyForkId2.bytes))
  }

  @Test
  fun `discovery finds nodes`() {
    val bootnode =
      MaruDiscoveryService(
        privateKeyBytes = key1,
        p2pConfig =
          P2P(
            ipAddress = IPV4,
            port = PORT1,
            discoveryPort = PORT2,
            bootnodes = emptyList(),
          ),
        forkIdProvider = forkIdProvider,
        metricsSystem = NoOpMetricsSystem(),
      )

    val enrString =
      getBootnodeEnrString(
        key1,
        IPV4,
        PORT2.toInt(),
        PORT1.toInt(),
      )

    val discoveryService2 =
      MaruDiscoveryService(
        privateKeyBytes = key2,
        p2pConfig =
          P2P(
            ipAddress = IPV4,
            port = PORT3,
            discoveryPort = PORT4,
            bootnodes = listOf(enrString),
          ),
        forkIdProvider = forkIdProvider,
        metricsSystem = NoOpMetricsSystem(),
      )

    val discoveryService3 =
      MaruDiscoveryService(
        privateKeyBytes = key3,
        p2pConfig =
          P2P(
            ipAddress = IPV4,
            port = PORT5,
            discoveryPort = PORT6,
            bootnodes = listOf(enrString),
          ),
        forkIdProvider = forkIdProvider,
        metricsSystem = NoOpMetricsSystem(),
      )

    try {
      bootnode.start()
      discoveryService2.start()
      discoveryService3.start()

      awaitPeerFound(discoveryService2, discoveryService3.localNodeRecord.nodeId)
      awaitPeerFound(discoveryService3, discoveryService2.localNodeRecord.nodeId)
      awaitPeerFound(bootnode, discoveryService2.localNodeRecord.nodeId)
      awaitPeerFound(bootnode, discoveryService3.localNodeRecord.nodeId)
    } finally {
      bootnode.stop()
      discoveryService2.stop()
      discoveryService3.stop()
    }
  }

  private fun awaitPeerFound(
    discoveryService: MaruDiscoveryService,
    expectedNodeId: Bytes,
  ) {
    Awaitility
      .await()
      .timeout(10, TimeUnit.SECONDS)
      .untilAsserted {
        val get = discoveryService.searchForPeers().get()
        assertThat(
          get
            .stream()
            .filter { it.nodeIdBytes == expectedNodeId }
            .count(),
        ).isGreaterThan(0L)
      }
  }
}
