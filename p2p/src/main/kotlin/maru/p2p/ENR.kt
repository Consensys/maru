/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.p2p

import org.apache.tuweni.bytes.Bytes32
import org.apache.tuweni.crypto.SECP256K1
import org.ethereum.beacon.discovery.schema.IdentitySchemaInterpreter
import org.ethereum.beacon.discovery.schema.NodeRecordBuilder
import org.ethereum.beacon.discovery.schema.NodeRecordFactory

object ENR {
  fun enrString(
    privateKeyBytes: ByteArray,
    seq: Int = 0,
    ipv4: String,
    ipv4UdpPort: Int,
    ipv4TcpPort: Int = ipv4UdpPort,
  ): String {
    val keyBytes =
      if (privateKeyBytes.size > 32) {
        privateKeyBytes.takeLast(32).toByteArray()
      } else {
        privateKeyBytes
      }
    val secretKey = SECP256K1.SecretKey.fromBytes(Bytes32.wrap(keyBytes))
    val nodeRecord =
      NodeRecordBuilder()
        .nodeRecordFactory(NodeRecordFactory(IdentitySchemaInterpreter.V4))
        .seq(seq)
        .secretKey(secretKey)
        .address(ipv4, ipv4UdpPort, ipv4TcpPort)
        .build()
    nodeRecord.sign(secretKey)
    return nodeRecord.asEnr()
  }
}
