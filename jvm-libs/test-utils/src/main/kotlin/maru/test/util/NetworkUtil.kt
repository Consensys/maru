/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.test.util

import java.net.ServerSocket

object NetworkUtil {
  fun findFreePorts(count: Int): List<UInt> {
    val ports = mutableListOf<UInt>()
    while (ports.size < count) {
      val freePort = findFreePort()
      if (!ports.contains(freePort)) {
        ports.add(freePort)
      }
    }
    return ports
  }

  fun findFreePort(): UInt =
    runCatching {
      ServerSocket(0).use { socket ->
        socket.reuseAddress = true
        socket.localPort.toUInt()
      }
    }.getOrElse {
      throw RuntimeException("Could not find a free port", it)
    }
}
