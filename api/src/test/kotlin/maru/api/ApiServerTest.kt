/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.api

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.util.concurrent.TimeUnit
import maru.VersionProvider
import maru.api.node.GetHealth
import maru.api.node.GetNetworkIdentity
import maru.api.node.GetNetworkIdentityResponse
import maru.api.node.GetPeer
import maru.api.node.GetPeerCount
import maru.api.node.GetPeerCountResponse
import maru.api.node.GetPeerResponse
import maru.api.node.GetPeers
import maru.api.node.GetPeersResponse
import maru.api.node.GetSyncingStatus
import maru.api.node.GetSyncingStatusResponse
import maru.api.node.GetVersion
import maru.api.node.GetVersionResponse
import maru.api.node.Metadata
import maru.api.node.NetworkIdentity
import maru.api.node.PeerCountData
import maru.api.node.PeerData
import maru.api.node.PeerMetaData
import maru.api.node.SyncingStatusData
import maru.api.node.VersionData
import maru.p2p.Peer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ApiServerTest {
  private val defaultObjectMapper = jacksonObjectMapper()

  private lateinit var apiServer: ApiServerImpl
  private lateinit var client: OkHttpClient
  private lateinit var apiServerUrl: String

  private val fakeNetworkDataProvider =
    object : NetworkDataProvider {
      override fun getNodeId(): String = "TEST_NODE_ID"

      override fun getEnr(): String = "TEST_ENR"

      override fun getNodeAddresses(): List<String> = listOf("TEST_NODE_ADDRESS")

      override fun getDiscoveryAddresses(): List<String> = listOf("TEST_DISCOVERY_ADDRESS")

      override fun getPeers(): List<Peer> =
        listOf(
          Peer(
            nodeId = "TEST_PEER_ID",
            enr = "TEST_PEER_ENR",
            address = "TEST_PEER_ADDRESS",
            status = Peer.PeerStatus.CONNECTED,
            direction = Peer.PeerDirection.OUTBOUND,
          ),
        )

      override fun getPeer(peerId: String): Peer? = getPeers().firstOrNull { it.nodeId == peerId }
    }

  private val fakeVersionProvider =
    object : VersionProvider {
      override fun getVersion(): String = "maru/1.0.0-test"
    }

  @BeforeEach
  fun beforeEach() {
    apiServer =
      ApiServerImpl(
        config = ApiServerImpl.Config(port = 0u),
        networkDataProvider = fakeNetworkDataProvider,
        versionProvider = fakeVersionProvider,
      )
    apiServer.start()
    apiServerUrl = "http://localhost:${apiServer.port()}"
    client = OkHttpClient.Builder().readTimeout(0, TimeUnit.SECONDS).build()
  }

  @AfterEach
  fun afterEach() {
    apiServer.stop()
  }

  @Test
  fun `test GetNetworkIdentity method`() {
    val url = (apiServerUrl + GetNetworkIdentity.ROUTE).toHttpUrl()
    val request = Request.Builder().url(url).build()
    val networkIdentity =
      NetworkIdentity(
        peerId = fakeNetworkDataProvider.getNodeId(),
        enr = fakeNetworkDataProvider.getEnr(),
        p2pAddresses = fakeNetworkDataProvider.getNodeAddresses(),
        discoveryAddresses = fakeNetworkDataProvider.getDiscoveryAddresses(),
        metadata =
          Metadata(
            seqNumber = "0",
            attnets = "0x",
            syncnets = "0x",
          ),
      )

    val expectedGetNetworkIdentityResponse = GetNetworkIdentityResponse(networkIdentity)
    val response = client.newCall(request).execute()
    assertThat(response).isNotNull
    assertThat(response.code).isEqualTo(200)
    val responseBody = response.body?.string()
    val getNetworkIdentityResponse =
      defaultObjectMapper.readValue(
        responseBody,
        GetNetworkIdentityResponse::class.java,
      )
    assertThat(getNetworkIdentityResponse).isEqualTo(expectedGetNetworkIdentityResponse)
  }

  @Test
  fun `test GetPeers method`() {
    val url = (apiServerUrl + GetPeers.ROUTE).toHttpUrl()
    val request = Request.Builder().url(url).build()
    val peerData =
      PeerData(
        peerId = "TEST_PEER_ID",
        enr = "TEST_PEER_ENR",
        lastSeenP2PAddress = "TEST_PEER_ADDRESS",
        state = "connected",
        direction = "outbound",
      )

    val expectedResponse = GetPeersResponse(data = listOf(peerData), meta = PeerMetaData(count = 1))
    val httpResponse = client.newCall(request).execute()
    assertThat(httpResponse).isNotNull
    assertThat(httpResponse.code).isEqualTo(200)
    val responseBody = httpResponse.body?.string()
    val response =
      defaultObjectMapper.readValue(
        responseBody,
        GetPeersResponse::class.java,
      )
    assertThat(response).isEqualTo(expectedResponse)
  }

  @Test
  fun `test GetPeerById method`() {
    val url = (apiServerUrl + GetPeer.ROUTE.replace("{${GetPeer.PEER_ID}}", "TEST_PEER_ID")).toHttpUrl()
    val request = Request.Builder().url(url).build()
    val peerData =
      PeerData(
        peerId = "TEST_PEER_ID",
        enr = "TEST_PEER_ENR",
        lastSeenP2PAddress = "TEST_PEER_ADDRESS",
        state = "connected",
        direction = "outbound",
      )

    val expectedResponse = GetPeerResponse(data = peerData)
    val httpResponse = client.newCall(request).execute()
    assertThat(httpResponse).isNotNull
    assertThat(httpResponse.code).isEqualTo(200)
    val responseBody = httpResponse.body?.string()
    val response =
      defaultObjectMapper.readValue(
        responseBody,
        GetPeerResponse::class.java,
      )
    assertThat(response).isEqualTo(expectedResponse)
  }

  @Test
  fun `test GetPeerById method when peer not found`() {
    val url = (apiServerUrl + GetPeer.ROUTE.replace("{${GetPeer.PEER_ID}}", "TEST_PEER_ID_2")).toHttpUrl()
    val request = Request.Builder().url(url).build()

    val httpResponse = client.newCall(request).execute()
    assertThat(httpResponse).isNotNull
    assertThat(httpResponse.code).isEqualTo(404)
    val responseBody = httpResponse.body?.string()
    val response =
      defaultObjectMapper.readValue(
        responseBody,
        ApiExceptionResponse::class.java,
      )
    assertThat(response).isEqualTo(ApiExceptionResponse(404, "Peer not found"))
  }

  @Test
  fun `test GetPeerCount method`() {
    val url = (apiServerUrl + GetPeerCount.ROUTE).toHttpUrl()
    val request = Request.Builder().url(url).build()
    val peerCountData =
      PeerCountData(
        disconnected = "0",
        connected = "1",
        connecting = "0",
        disconnecting = "0",
      )

    val expectedResponse = GetPeerCountResponse(data = peerCountData)
    val httpResponse = client.newCall(request).execute()
    assertThat(httpResponse).isNotNull
    assertThat(httpResponse.code).isEqualTo(200)
    val responseBody = httpResponse.body?.string()
    val response =
      defaultObjectMapper.readValue(
        responseBody,
        GetPeerCountResponse::class.java,
      )
    assertThat(response).isEqualTo(expectedResponse)
  }

  @Test
  fun `test GetVersion method`() {
    val url = (apiServerUrl + GetVersion.ROUTE).toHttpUrl()
    val request = Request.Builder().url(url).build()
    val expectedResponse = GetVersionResponse(data = VersionData(version = fakeVersionProvider.getVersion()))
    val httpResponse = client.newCall(request).execute()
    assertThat(httpResponse).isNotNull
    assertThat(httpResponse.code).isEqualTo(200)
    val responseBody = httpResponse.body?.string()
    val response =
      defaultObjectMapper.readValue(
        responseBody,
        GetVersionResponse::class.java,
      )
    assertThat(response).isEqualTo(expectedResponse)
  }

  @Test
  fun `test GetSyncingStatus method`() {
    val url = (apiServerUrl + GetSyncingStatus.ROUTE).toHttpUrl()
    val request = Request.Builder().url(url).build()
    val expectedResponse =
      GetSyncingStatusResponse(
        data =
          SyncingStatusData(
            headSlot = "12345678",
            syncDistance = "0",
            isSyncing = false,
            isOptimistic = false,
            elOffline = false,
          ),
      )
    val httpResponse = client.newCall(request).execute()
    assertThat(httpResponse).isNotNull
    assertThat(httpResponse.code).isEqualTo(200)
    val responseBody = httpResponse.body?.string()
    val response =
      defaultObjectMapper.readValue(
        responseBody,
        GetSyncingStatusResponse::class.java,
      )
    assertThat(response).isEqualTo(expectedResponse)
  }

  @Test
  fun `test GetHealth method`() {
    val url = (apiServerUrl + GetHealth.ROUTE).toHttpUrl()
    val request = Request.Builder().url(url).build()
    val expectedResponse = "Node is ready"
    val httpResponse = client.newCall(request).execute()
    assertThat(httpResponse).isNotNull
    assertThat(httpResponse.code).isEqualTo(200)
    val response = httpResponse.body?.string()
    assertThat(response).isEqualTo(expectedResponse)
  }
}
