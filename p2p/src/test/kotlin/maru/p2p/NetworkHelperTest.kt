/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.p2p

import java.net.Inet4Address
import java.net.InetAddress
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class NetworkHelperTest {
  @Test
  fun `listNetworkAddresses returns network addresses`() {
    NetworkHelper.listNetworkAddresses(excludeLoopback = true).also { addresses ->
      assertThat(addresses).allMatch { !it.isLoopbackAddress }
    }

    NetworkHelper.listNetworkAddresses(excludeLoopback = false).also { addresses ->
      assertThat(addresses).anyMatch { it.isLoopbackAddress }
    }
  }

  @Test
  fun `listIpV4 returns network addresses`() {
    NetworkHelper.listIpsV4(excludeLoopback = true).also { addresses ->
      assertThat(addresses).doesNotContain("127.0.0.1")
    }

    NetworkHelper.listIpsV4(excludeLoopback = false).also { addresses ->
      assertThat(addresses).contains("127.0.0.1")
    }
  }

  @Test
  fun `selectIpV4ForP2P returns targetIpV4 if present in local interfaces`() {
    val localIp = NetworkHelper.listIpsV4().first()
    val result = NetworkHelper.selectIpV4ForP2P(localIp)
    assertThat(result).isEqualTo(localIp)
  }

  @Test
  fun `selectIpV4ForP2P returns first IP if targetIpV4 is 0_0_0_0`() {
    val ips = NetworkHelper.listIpsV4()
    val result = NetworkHelper.selectIpV4ForP2P("0.0.0.0")
    assertThat(result).isEqualTo(ips.first())
  }

  @Test
  fun `loopBackLastComparator favours not loopback`() {
    listOf<InetAddress>(
      Inet4Address.getByName("127.0.0.1"),
      Inet4Address.getByName("100.100.0.1"),
    ).sortedWith(NetworkHelper.loopBackLastComparator)
      .also { sorted ->
        assertThat(sorted).isEqualTo(
          listOf<InetAddress>(
            Inet4Address.getByName("100.100.0.1"),
            Inet4Address.getByName("127.0.0.1"),
          ),
        )
      }
  }

  @Test
  fun `selectIpV4ForP2P throws if targetIpV4 is not present`() {
    val fakeIp = "192.0.2.123" // TEST-NET-1, unlikely to exist
    assertThatThrownBy { NetworkHelper.selectIpV4ForP2P(fakeIp) }
      .isInstanceOf(IllegalArgumentException::class.java)
      .hasMessageContaining("targetIpV4=$fakeIp not found in machine interfaces")
  }
}
