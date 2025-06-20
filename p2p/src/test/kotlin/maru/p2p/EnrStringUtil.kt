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

import java.util.Base64
import org.apache.tuweni.bytes.Bytes32
import org.apache.tuweni.crypto.SECP256K1
import org.ethereum.beacon.discovery.schema.IdentitySchemaInterpreter
import org.ethereum.beacon.discovery.schema.NodeRecordBuilder
import org.ethereum.beacon.discovery.schema.NodeRecordFactory

fun getBootnodeEnrString(
  privateKeyBytes: ByteArray,
  ipv4: String,
  discPort: Int,
  tcpPort: Int,
): String {
  val secretKey = SECP256K1.SecretKey.fromBytes(Bytes32.wrap(privateKeyBytes))
  val bootnodeNR =
    NodeRecordBuilder()
      .nodeRecordFactory(NodeRecordFactory(IdentitySchemaInterpreter.V4))
      .seq(1)
      .secretKey(secretKey)
      .address(ipv4, discPort, tcpPort)
      .build()
  val enr = bootnodeNR.serialize()
  val encode = Base64.getUrlEncoder().encode(enr.toArray())
  val enrString = "enr:${String(encode)}"
  return enrString
}
