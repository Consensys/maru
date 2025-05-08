/*
   Copyright 2025 Consensys Software Inc.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */
package maru.p2p

import io.libp2p.core.PeerId
import io.libp2p.core.crypto.unmarshalPrivateKey
import java.lang.Thread.sleep
import java.util.concurrent.TimeUnit
import maru.config.P2P
import org.apache.tuweni.bytes.Bytes
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatNoException
import org.awaitility.Awaitility
import org.junit.jupiter.api.Test
import tech.pegasys.teku.networking.p2p.libp2p.LibP2PNodeId
import tech.pegasys.teku.networking.p2p.libp2p.MultiaddrPeerAddress
import tech.pegasys.teku.networking.p2p.network.PeerAddress
import tech.pegasys.teku.networking.p2p.network.config.GeneratingFilePrivateKeySource
import tech.pegasys.teku.networking.p2p.peer.DisconnectReason

private const val GOSSIP_MESSAGE = "0xdeadbeef"

private const val IPV4 = "127.0.0.1"

private const val PORT1 = "9234"
private const val PORT2 = "9235"
private const val PORT3 = "9236"

private const val PRIVATE_KEY1: String = "0x0802122012c0b113e2b0c37388e2b484112e13f05c92c4471e3ee1dfaa368fa5045325b2"
private const val PRIVATE_KEY2: String = "0x0802122100f3d2fffa99dc8906823866d96316492ebf7a8478713a89a58b7385af85b088a1"
private const val PRIVATE_KEY3: String = "0x080212204437acb8e84bc346f7640f239da84abe99bc6f97b7855f204e34688d2977fd57"

private const val PEER_ID_NODE_1: String = "16Uiu2HAmPRfinavM2jE9BSkCagBGStJ2SEkPPm6fxFVMdCQebzt6"
private const val PEER_ID_NODE_2: String = "16Uiu2HAmVXtqhevTAJqZucPbR2W4nCMpetrQASgjZpcxDEDaUPPt"
private const val PEER_ID_NODE_3: String = "16Uiu2HAkzq767a82zfyUz4VLgPbFrxSQBrdmUYxgNDbwgvmjwWo5"

// TODO: to make these tests reliable it would be good if the ports were not hardcoded, but free ports chosen
private const val PEER_ADDRESS_NODE_1: String = "/ip4/$IPV4/tcp/$PORT1/p2p/$PEER_ID_NODE_1"
private const val PEER_ADDRESS_NODE_2: String = "/ip4/$IPV4/tcp/$PORT2/p2p/$PEER_ID_NODE_2"
private const val PEER_ADDRESS_NODE_3: String = "/ip4/$IPV4/tcp/$PORT3/p2p/$PEER_ID_NODE_3"

class P2PTest {
  companion object {
    private val key1 = Bytes.fromHexString(PRIVATE_KEY1).toArray()
    private val key2 = Bytes.fromHexString(PRIVATE_KEY2).toArray()
    private val key3 = Bytes.fromHexString(PRIVATE_KEY3).toArray()
  }

  @Test
  fun `static peer can be added`() {
    val privateKeyBytes = GeneratingFilePrivateKeySource("/tmp/key3.txt").privateKeyBytes
    val toHexString = privateKeyBytes.toHexString()
    val privKey = unmarshalPrivateKey(privateKeyBytes.toArrayUnsafe())
    val peerId = PeerId.fromPubKey(privKey.publicKey()).toBase58()

    println("peerId: $peerId")
    println("private key: $toHexString")

    val p2pManager1 = P2PManager(key1, P2P(IPV4, PORT1, emptyList()))
    val p2pManager2 = P2PManager(key2, P2P(IPV4, PORT2, emptyList()))
    try {
      p2pManager1.start()
      val p2pNetwork1 = p2pManager1.p2pNetwork

      p2pManager2.start()
      val p2pNetwork2 = p2pManager2.p2pNetwork

      p2pManager1.addStaticPeer(MultiaddrPeerAddress.fromAddress(PEER_ADDRESS_NODE_2))

      Awaitility
        .await()
        .timeout(
          6000,
          TimeUnit.MILLISECONDS,
        ).untilAsserted { assertThat(p2pNetwork1.peerCount).isEqualTo(1) }
      Awaitility
        .await()
        .timeout(
          6000,
          TimeUnit.MILLISECONDS,
        ).untilAsserted { assertThat(p2pNetwork2.peerCount).isEqualTo(1) }
    } finally {
      p2pManager1.stop()
      p2pManager2.stop()
    }
  }

  @Test
  fun `static peers can be removed`() {
    val p2pManager1 = P2PManager(key1, P2P(IPV4, PORT1, emptyList()))
    val p2pManager2 = P2PManager(key2, P2P(IPV4, PORT2, emptyList()))
    try {
      p2pManager1.start()
      val p2pNetwork1 = p2pManager1.p2pNetwork

      p2pManager2.start()
      val p2pNetwork2 = p2pManager2.p2pNetwork

      p2pManager1.addStaticPeer(MultiaddrPeerAddress.fromAddress(PEER_ADDRESS_NODE_2))

      Awaitility
        .await()
        .timeout(
          6000,
          TimeUnit.MILLISECONDS,
        ).untilAsserted { assertThat(p2pNetwork1.peerCount).isEqualTo(1) }
      Awaitility
        .await()
        .timeout(
          6000,
          TimeUnit.MILLISECONDS,
        ).untilAsserted { assertThat(p2pNetwork2.peerCount).isEqualTo(1) }

      p2pManager1.removeStaticPeer(MultiaddrPeerAddress.fromAddress(PEER_ADDRESS_NODE_2))

      Awaitility
        .await()
        .timeout(
          6000,
          TimeUnit.MILLISECONDS,
        ).untilAsserted { assertThat(p2pNetwork1.peerCount).isEqualTo(0) }
      Awaitility
        .await()
        .timeout(
          6000,
          TimeUnit.MILLISECONDS,
        ).untilAsserted { assertThat(p2pNetwork2.peerCount).isEqualTo(0) }
    } finally {
      p2pManager1.stop()
      p2pManager2.stop()
    }
  }

  @Test
  fun `static peers can be configured`() {
    val p2pManager1 = P2PManager(key1, P2P(IPV4, PORT1, emptyList()))
    val p2pManager2 = P2PManager(key2, P2P(IPV4, PORT2, listOf(PEER_ADDRESS_NODE_1)))
    try {
      p2pManager1.start()
      val p2pNetwork1 = p2pManager1.p2pNetwork

      p2pManager2.start()
      val p2pNetwork2 = p2pManager2.p2pNetwork

      Awaitility
        .await()
        .timeout(
          6000,
          TimeUnit.MILLISECONDS,
        ).untilAsserted { assertThat(p2pNetwork1.peerCount).isEqualTo(1) }
      Awaitility
        .await()
        .timeout(
          6000,
          TimeUnit.MILLISECONDS,
        ).untilAsserted { assertThat(p2pNetwork2.peerCount).isEqualTo(1) }
    } finally {
      p2pManager1.stop()
      p2pManager2.stop()
    }
  }

  @Test
  fun `static peers reconnect`() {
    val p2pManager1 = P2PManager(key1, P2P(IPV4, PORT1, emptyList()))
    val p2pManager2 = P2PManager(key2, P2P(IPV4, PORT2, listOf(PEER_ADDRESS_NODE_1)))
    try {
      p2pManager1.start()
      val p2pNetwork1 = p2pManager1.p2pNetwork

      p2pManager2.start()
      val p2pNetwork2 = p2pManager2.p2pNetwork

      Awaitility
        .await()
        .timeout(
          6000,
          TimeUnit.MILLISECONDS,
        ).untilAsserted { assertThat(p2pNetwork1.peerCount).isEqualTo(1) }
      Awaitility
        .await()
        .timeout(
          6000,
          TimeUnit.MILLISECONDS,
        ).untilAsserted { assertThat(p2pNetwork2.peerCount).isEqualTo(1) }

      p2pNetwork1
        .getPeer(LibP2PNodeId(PeerId.fromBase58(PEER_ID_NODE_2)))
        .get()
        .disconnectCleanly(DisconnectReason.TOO_MANY_PEERS)
        .thenPeek { assertThat(p2pNetwork1.peerCount).isEqualTo(0) }

      Awaitility
        .await()
        .timeout(
          6000,
          TimeUnit.MILLISECONDS,
        ).untilAsserted { assertThat(p2pNetwork1.peerCount).isEqualTo(1) }
      Awaitility
        .await()
        .timeout(
          6000,
          TimeUnit.MILLISECONDS,
        ).untilAsserted { assertThat(p2pNetwork2.peerCount).isEqualTo(1) }
    } finally {
      p2pManager1.stop()
      p2pManager2.stop()
    }
  }

  @Test
  fun `two peers can gossip with each other`() {
    val p2pManager1 = P2PManager(key1, P2P(IPV4, PORT1, emptyList()))
    val p2pManager2 = P2PManager(key2, P2P(IPV4, PORT2, listOf(PEER_ADDRESS_NODE_1)))
    try {
      p2pManager1.start()
      val p2pNetwork1 = p2pManager1.p2pNetwork
      p2pNetwork1.subscribe("topic", TestTopicHandler())

      p2pManager2.start()
      val p2pNetwork2 = p2pManager2.p2pNetwork
      val testTopicHandler2 = TestTopicHandler()
      p2pNetwork2.subscribe("topic", testTopicHandler2)

      Awaitility
        .await()
        .timeout(
          6000,
          TimeUnit.MILLISECONDS,
        ).untilAsserted { assertThat(p2pNetwork1.peerCount).isEqualTo(1) }
      Awaitility
        .await()
        .timeout(
          6000,
          TimeUnit.MILLISECONDS,
        ).untilAsserted { assertThat(p2pNetwork2.peerCount).isEqualTo(1) }

      p2pNetwork1.gossip("topic", Bytes.fromHexString(GOSSIP_MESSAGE))

      assertThat(
        testTopicHandler2.dataFuture.get(10, TimeUnit.MILLISECONDS),
      ).isEqualTo(Bytes.fromHexString(GOSSIP_MESSAGE))
    } finally {
      p2pManager1.stop()
      p2pManager2.stop()
    }
  }

  @Test
  fun `peer receiving gossip passes message on`() {
    val p2pManager1 = P2PManager(key1, P2P(IPV4, PORT1, emptyList()))
    val p2pManager2 =
      P2PManager(key2, P2P(IPV4, PORT2, listOf(PEER_ADDRESS_NODE_1, PEER_ADDRESS_NODE_3)))
    val p2pManager3 = P2PManager(key3, P2P(IPV4, PORT3, emptyList()))
    try {
      p2pManager1.start()
      val p2pNetwork1 = p2pManager1.p2pNetwork
      p2pNetwork1.subscribe("topic", TestTopicHandler())

      p2pManager2.start()
      val p2pNetwork2 = p2pManager2.p2pNetwork
      val testTopicHandler2 = TestTopicHandler()
      p2pNetwork2.subscribe("topic", testTopicHandler2)

      p2pManager3.start()
      val p2pNetwork3 = p2pManager3.p2pNetwork
      val testTopicHandler3 = TestTopicHandler()
      p2pNetwork3.subscribe("topic", testTopicHandler3)

      Awaitility
        .await()
        .timeout(
          6000,
          TimeUnit.MILLISECONDS,
        ).untilAsserted {
          assertThat(
            p2pNetwork1.isConnected(
              PeerAddress(
                LibP2PNodeId(
                  PeerId.fromBase58(
                    PEER_ID_NODE_2,
                  ),
                ),
              ),
            ),
          ).isTrue()
        }
      Awaitility
        .await()
        .timeout(
          6000,
          TimeUnit.MILLISECONDS,
        ).untilAsserted {
          assertThat(
            p2pNetwork3.isConnected(
              PeerAddress(
                LibP2PNodeId(
                  PeerId.fromBase58(
                    PEER_ID_NODE_2,
                  ),
                ),
              ),
            ),
          ).isTrue()
        }
      assertThat(p2pNetwork1.peerCount).isEqualTo(1)
      assertThat(p2pNetwork2.peerCount).isEqualTo(2)
      assertThat(p2pNetwork3.peerCount).isEqualTo(1)

      sleep(100)

      p2pNetwork1.gossip("topic", Bytes.fromHexString(GOSSIP_MESSAGE))

      assertThat(
        testTopicHandler2.dataFuture.get(100, TimeUnit.MILLISECONDS),
      ).isEqualTo(Bytes.fromHexString(GOSSIP_MESSAGE))
      assertThat(
        testTopicHandler3.dataFuture.get(200, TimeUnit.MILLISECONDS),
      ).isEqualTo(Bytes.fromHexString(GOSSIP_MESSAGE))
    } finally {
      p2pManager1.stop()
      p2pManager2.stop()
      p2pManager3.stop()
    }
  }

  @Test
  fun `peer can send a request`() {
    val p2pManager1 = P2PManager(key1, P2P(IPV4, PORT1, emptyList()))
    val p2pManager2 = P2PManager(key2, P2P(IPV4, PORT2, listOf(PEER_ADDRESS_NODE_1)))
    try {
      p2pManager1.start()
      val p2pNetwork1 = p2pManager1.p2pNetwork

      p2pManager2.start()
      val p2pNetwork2 = p2pManager2.p2pNetwork

      Awaitility
        .await()
        .timeout(
          6000,
          TimeUnit.MILLISECONDS,
        ).untilAsserted { assertThat(p2pNetwork1.peerCount).isEqualTo(1) }
      Awaitility
        .await()
        .timeout(
          6000,
          TimeUnit.MILLISECONDS,
        ).untilAsserted { assertThat(p2pNetwork2.peerCount).isEqualTo(1) }

      val peer =
        p2pNetwork2.getPeer(LibP2PNodeId(PeerId.fromBase58(PEER_ID_NODE_1))).get()
      val request = Bytes.wrap(byteArrayOf(0, 0, 1, 2, 3, 4))
      val maruRpcResponseHandler = MaruRpcResponseHandler()
      val responseFuture =
        peer.sendRequest(
          MaruRpcMethod(),
          request,
          maruRpcResponseHandler,
        )
      responseFuture.thenPeek {
        it.rpcStream.closeWriteStream()
      }

      assertThatNoException().isThrownBy { responseFuture.get(500, TimeUnit.MILLISECONDS) }
      assertThat(
        maruRpcResponseHandler.response().get(500, TimeUnit.MILLISECONDS),
      ).isEqualTo(request.reverse())
    } finally {
      p2pManager1.stop()
      p2pManager2.stop()
    }
  }
}
