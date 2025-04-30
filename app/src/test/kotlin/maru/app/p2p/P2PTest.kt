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
package maru.app.p2p

import io.libp2p.core.PeerId
import java.lang.Thread.sleep
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import maru.p2p.MaruRpcResponseHandler
import maru.p2p.P2PNetworkBuilder.Companion.rpcMethod
import maru.p2p.TestTopicHandler
import maru.testutils.MaruFactory
import maru.testutils.besu.BesuFactory
import org.apache.logging.log4j.LogManager
import org.apache.tuweni.bytes.Bytes
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatNoException
import org.hyperledger.besu.tests.acceptance.dsl.condition.net.NetConditions
import org.hyperledger.besu.tests.acceptance.dsl.node.BesuNode
import org.hyperledger.besu.tests.acceptance.dsl.node.cluster.Cluster
import org.hyperledger.besu.tests.acceptance.dsl.transaction.net.NetTransactions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tech.pegasys.teku.networking.p2p.libp2p.LibP2PNodeId
import tech.pegasys.teku.networking.p2p.libp2p.MultiaddrPeerAddress

private const val ORIGINAL_MESSAGE = "deaddeadbeefbeef"

class P2PTest {
  companion object {
    const val PRIVATE_KEY1: String = "0x0802122012c0b113e2b0c37388e2b484112e13f05c92c4471e3ee1dfaa368fa5045325b2"
    const val PRIVATE_KEY2: String = "0x0802122100f3d2fffa99dc8906823866d96316492ebf7a8478713a89a58b7385af85b088a1"

    const val PEER_ID_NODE_1: String = "16Uiu2HAmPRfinavM2jE9BSkCagBGStJ2SEkPPm6fxFVMdCQebzt6"
    const val PEER_ID_NODE_2: String = "16Uiu2HAmVXtqhevTAJqZucPbR2W4nCMpetrQASgjZpcxDEDaUPPt"

    const val PEER_ADDRESS_NODE_1: String = "/ip4/127.0.0.1/tcp/9234/p2p/$PEER_ID_NODE_1"
    const val PEER_ADDRESS_NODE_2: String = "/ip4/127.0.0.1/tcp/9235/p2p/$PEER_ID_NODE_2"

    const val PEER_ADDRESS_NODE_1_IPV6: String = "/ip6/0:0:0:0:0:0:0:1/tcp/9234/p2p/$PEER_ID_NODE_1"
    const val PEER_ADDRESS_NODE_2_IPV6: String = "/ip6/0:0:0:0:0:0:0:1/tcp/9235/p2p/$PEER_ID_NODE_2"

    private lateinit var key1File: String
    private lateinit var key2File: String

    @JvmStatic
    @BeforeAll
    fun beforeAll() {
      val file1: Path = Files.createTempFile("test1_", ".txt")
      key1File = file1.toAbsolutePath().toString()
      Files.writeString(file1, PRIVATE_KEY1)

      val file2: Path = Files.createTempFile("test2_", ".txt")
      key2File = file2.toAbsolutePath().toString()
      Files.writeString(file2, PRIVATE_KEY2)
    }
  }

  private var cluster = Cluster(NetConditions(NetTransactions()))
  private lateinit var besuNode1: BesuNode
  private lateinit var besuNode2: BesuNode
  private val log = LogManager.getLogger(this.javaClass)

  @BeforeEach
  fun setUp() {
    besuNode1 = BesuFactory.buildTestBesu()
//    val netConditions = NetConditions(NetTransactions())
//    besuNode1.awaitPeerDiscovery(netConditions.awaitPeerCount(0))
//    besuNode2 = BesuFactory.copyTestBesu(besuNode1)
    cluster = Cluster(NetConditions(NetTransactions()))
    cluster.start(besuNode1/*, besuNode2 */)
    System.setProperty("acctests.runBesuAsProcess", "true")
    System.setProperty("maru.reconnect.delay", "100")
  }

  @AfterEach
  fun tearDown() {
    cluster.close()
    sleep(1000)
  }

  @Test
  fun `static peer can be added`() {
    val maruNode1 =
      MaruFactory.buildTestMaru(
        ethereumJsonRpcUrl = besuNode1.jsonRpcBaseUrl().get(),
        engineApiRpc = besuNode1.engineRpcUrl().get(),
        networks = listOf(PEER_ADDRESS_NODE_1),
        staticPeers = listOf(),
        privateKeyFile = key1File,
      )
    maruNode1.start()
    val p2PNetwork1 = maruNode1.p2pManager.p2PNetwork

    val maruNode2 =
      MaruFactory.buildTestMaru(
        ethereumJsonRpcUrl = besuNode1.jsonRpcBaseUrl().get(),
        engineApiRpc = besuNode1.engineRpcUrl().get(),
        networks = listOf(PEER_ADDRESS_NODE_2),
        staticPeers = listOf(),
        privateKeyFile = key2File,
      )
    maruNode2.start()
    val p2PNetwork2 = maruNode2.p2pManager.p2PNetwork

    maruNode1.p2pManager.addStaticPeer(MultiaddrPeerAddress.fromAddress(PEER_ADDRESS_NODE_2))

    sleep(200) // time needed to connect

    assertThat(p2PNetwork1!!.peerCount).isEqualTo(1)
    assertThat(p2PNetwork2!!.peerCount).isEqualTo(1)

    maruNode1.stop()
    maruNode2.stop()
  }

  @Test
  fun `static peers can be removed`() {
    val maruNode1 =
      MaruFactory.buildTestMaru(
        ethereumJsonRpcUrl = besuNode1.jsonRpcBaseUrl().get(),
        engineApiRpc = besuNode1.engineRpcUrl().get(),
        networks = listOf(PEER_ADDRESS_NODE_1),
        staticPeers = listOf(),
        privateKeyFile = key1File,
      )
    maruNode1.start()
    val p2PNetwork1 = maruNode1.p2pManager.p2PNetwork

    val maruNode2 =
      MaruFactory.buildTestMaru(
        ethereumJsonRpcUrl = besuNode1.jsonRpcBaseUrl().get(),
        engineApiRpc = besuNode1.engineRpcUrl().get(),
        networks = listOf(PEER_ADDRESS_NODE_2),
        staticPeers = listOf(),
        privateKeyFile = key2File,
      )
    maruNode2.start()
    val p2PNetwork2 = maruNode2.p2pManager.p2PNetwork

    maruNode1.p2pManager.addStaticPeer(MultiaddrPeerAddress.fromAddress(PEER_ADDRESS_NODE_2))

    sleep(200)

    assertThat(p2PNetwork1!!.peerCount).isEqualTo(1)
    assertThat(p2PNetwork2!!.peerCount).isEqualTo(1)

    maruNode1.p2pManager.removeStaticPeer(MultiaddrPeerAddress.fromAddress(PEER_ADDRESS_NODE_2))

    sleep(200)

    assertThat(p2PNetwork1.peerCount).isEqualTo(0)
    assertThat(p2PNetwork2.peerCount).isEqualTo(0)

    maruNode1.stop()
    maruNode2.stop()
  }

  @Test
  fun `static peers can be configured`() {
    val maruNode1 =
      MaruFactory.buildTestMaru(
        ethereumJsonRpcUrl = besuNode1.jsonRpcBaseUrl().get(),
        engineApiRpc = besuNode1.engineRpcUrl().get(),
        networks = listOf(PEER_ADDRESS_NODE_1),
        staticPeers = listOf(PEER_ADDRESS_NODE_2),
        privateKeyFile = key1File,
      )
    maruNode1.start()
    val p2PNetwork1 = maruNode1.p2pManager.p2PNetwork

    val maruNode2 =
      MaruFactory.buildTestMaru(
        ethereumJsonRpcUrl = besuNode1.jsonRpcBaseUrl().get(),
        engineApiRpc = besuNode1.engineRpcUrl().get(),
        networks = listOf(PEER_ADDRESS_NODE_2),
        staticPeers = listOf(),
        privateKeyFile = key2File,
      )
    maruNode2.start()
    val p2PNetwork2 = maruNode2.p2pManager.p2PNetwork

    sleep(200)

    assertThat(p2PNetwork1!!.peerCount).isEqualTo(1)
    assertThat(p2PNetwork2!!.peerCount).isEqualTo(1)

    maruNode1.stop()
    maruNode2.stop()
  }

  @Test
  fun `static peers reconnected after restart`() {
    val maruNode1 =
      MaruFactory.buildTestMaru(
        ethereumJsonRpcUrl = besuNode1.jsonRpcBaseUrl().get(),
        engineApiRpc = besuNode1.engineRpcUrl().get(),
        networks = listOf(PEER_ADDRESS_NODE_1),
        staticPeers = listOf(PEER_ADDRESS_NODE_2),
        privateKeyFile = key1File,
      )
    maruNode1.start()
    val p2PNetwork1 = maruNode1.p2pManager.p2PNetwork

    val maruNode2 =
      MaruFactory.buildTestMaru(
        ethereumJsonRpcUrl = besuNode1.jsonRpcBaseUrl().get(),
        engineApiRpc = besuNode1.engineRpcUrl().get(),
        networks = listOf(PEER_ADDRESS_NODE_2),
        staticPeers = listOf(),
        privateKeyFile = key2File,
      )
    maruNode2.start()
    val p2PNetwork2 = maruNode2.p2pManager.p2PNetwork

    sleep(200)

    assertThat(p2PNetwork1!!.peerCount).isEqualTo(1)
    assertThat(p2PNetwork2!!.peerCount).isEqualTo(1)

    maruNode1.stop()

    sleep(200)

    assertThat(p2PNetwork2.peerCount).isEqualTo(0)

    maruNode1.start()
    val newP2PNetwork = maruNode1.p2pManager.p2PNetwork

    sleep(200)

    assertThat(newP2PNetwork!!.peerCount).isEqualTo(1)
    assertThat(p2PNetwork2.peerCount).isEqualTo(1)

    maruNode1.stop()
    maruNode2.stop()
  }

  @Test
  fun `static peers can be configured (ipv6)`() {
    val maruNode1 =
      MaruFactory.buildTestMaru(
        ethereumJsonRpcUrl = besuNode1.jsonRpcBaseUrl().get(),
        engineApiRpc = besuNode1.engineRpcUrl().get(),
        networks = listOf(PEER_ADDRESS_NODE_1_IPV6),
        staticPeers = listOf(PEER_ADDRESS_NODE_2_IPV6),
        privateKeyFile = key1File,
      )
    maruNode1.start()
    val p2PNetwork1 = maruNode1.p2pManager.p2PNetwork

    val maruNode2 =
      MaruFactory.buildTestMaru(
        ethereumJsonRpcUrl = besuNode1.jsonRpcBaseUrl().get(),
        engineApiRpc = besuNode1.engineRpcUrl().get(),
        networks = listOf(PEER_ADDRESS_NODE_2_IPV6),
        staticPeers = listOf(),
        privateKeyFile = key2File,
      )
    maruNode2.start()
    val p2PNetwork2 = maruNode2.p2pManager.p2PNetwork

    sleep(200)

    assertThat(p2PNetwork1!!.peerCount).isEqualTo(1)
    assertThat(p2PNetwork2!!.peerCount).isEqualTo(1)

    maruNode1.stop()
    maruNode2.stop()
  }

  @Test
  fun `two peers can gossip with each other`() {
    val maruNode1 =
      MaruFactory.buildTestMaru(
        ethereumJsonRpcUrl = besuNode1.jsonRpcBaseUrl().get(),
        engineApiRpc = besuNode1.engineRpcUrl().get(),
        networks = listOf(PEER_ADDRESS_NODE_1),
        staticPeers = listOf(),
        privateKeyFile = key1File,
      )
    maruNode1.start()
    val p2PNetwork1 = maruNode1.p2pManager.p2PNetwork

    val maruNode2 =
      MaruFactory.buildTestMaru(
        ethereumJsonRpcUrl = besuNode1.jsonRpcBaseUrl().get(),
        engineApiRpc = besuNode1.engineRpcUrl().get(),
        networks = listOf(PEER_ADDRESS_NODE_2),
        staticPeers = listOf(PEER_ADDRESS_NODE_1),
        privateKeyFile = key2File,
      )
    maruNode2.start()
    val p2PNetwork2 = maruNode2.p2pManager.p2PNetwork

    sleep(500)

    assertThat(p2PNetwork1!!.peerCount).isEqualTo(1)
    assertThat(p2PNetwork2!!.peerCount).isEqualTo(1)

    // this throws an exception if we do not have at least one peer that is subscribed to the topic
    p2PNetwork1.gossip("topic", Bytes.fromHexString(ORIGINAL_MESSAGE))

    assertThat(
      TestTopicHandler.dataFuture.get(4000, TimeUnit.MILLISECONDS),
    ).isEqualTo(Bytes.fromHexString(ORIGINAL_MESSAGE))

    maruNode1.stop()
    maruNode2.stop()
  }

  @Test
  fun `peer can send a request`() {
    val maruNode1 =
      MaruFactory.buildTestMaru(
        ethereumJsonRpcUrl = besuNode1.jsonRpcBaseUrl().get(),
        engineApiRpc = besuNode1.engineRpcUrl().get(),
        networks = listOf(PEER_ADDRESS_NODE_1),
        staticPeers = listOf(),
        privateKeyFile = key1File,
      )
    maruNode1.start()
    val p2PNetwork1 = maruNode1.p2pManager.p2PNetwork

    val maruNode2 =
      MaruFactory.buildTestMaru(
        ethereumJsonRpcUrl = besuNode1.jsonRpcBaseUrl().get(),
        engineApiRpc = besuNode1.engineRpcUrl().get(),
        networks = listOf(PEER_ADDRESS_NODE_2),
        staticPeers = listOf(PEER_ADDRESS_NODE_1),
        privateKeyFile = key2File,
      )
    maruNode2.start()
    val p2PNetwork2 = maruNode2.p2pManager.p2PNetwork

    sleep(500)

    assertThat(p2PNetwork1!!.peerCount).isEqualTo(1)
    assertThat(p2PNetwork2!!.peerCount).isEqualTo(1)

    val peer =
      maruNode2.p2pManager.p2PNetwork!!
        .getPeer(LibP2PNodeId(PeerId.fromBase58(PEER_ID_NODE_1)))
        .get()
    val request = Bytes.wrap(byteArrayOf(0, 0, 1, 2, 3, 4))
    val maruRpcResponseHandler = MaruRpcResponseHandler()
    val responseFuture =
      peer.sendRequest(
        rpcMethod,
        request,
        maruRpcResponseHandler,
      )
    responseFuture.thenPeek {
      it.rpcStream.closeWriteStream()
    } // TODO: this basically signals that we are done sending the request
    // if we do expect a response we can get the outgoing request handler (it.getRequiredOutgoingRequestHandler()) to check that (timeout, etc)

    assertThatNoException().isThrownBy { responseFuture.get(500, TimeUnit.MILLISECONDS) }
    assertThat(maruRpcResponseHandler.response().get(500, TimeUnit.MILLISECONDS)).isEqualTo(request.reverse())

    maruNode1.stop()
    maruNode2.stop()
  }
}
