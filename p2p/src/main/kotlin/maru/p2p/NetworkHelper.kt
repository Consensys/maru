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
import java.net.NetworkInterface

object NetworkHelper {
  fun listNetworkAddresses(excludeLoopback: Boolean = true): List<InetAddress> =
    NetworkInterface
      .getNetworkInterfaces()
      .toList()
      .flatMap { it.inetAddresses.toList() }
      .filter {
        if (excludeLoopback && it.isLoopbackAddress) false else true
      }

  fun listIpsV4(excludeLoopback: Boolean = true): List<String> =
    listNetworkAddresses(excludeLoopback)
      .filter { it is Inet4Address }
      .map { it.hostAddress }

  fun hasInterfaceWithIpV4(ipV4: String): Boolean = listIpsV4(excludeLoopback = false).contains(ipV4)

  fun selectIpV4ForP2P(targetIpV4: String): String {
    require(Inet4Address.getByName(targetIpV4) != null) { "targetIpV4 address is null" }
    val ips = listIpsV4(excludeLoopback = false)
    check(ips.isNotEmpty()) { "No IPv4 addresses found on the local machine." }

    if (ips.contains(targetIpV4)) {
      return targetIpV4
    } else if (targetIpV4 == "0.0.0.0") {
      return ips.first()
    } else {
      throw IllegalArgumentException(
        "targetIpV4=$targetIpV4 not found in machine interfaces, available ips= ${
          ips.joinToString(
            ", ",
          )
        }",
      )
    }
  }
}
