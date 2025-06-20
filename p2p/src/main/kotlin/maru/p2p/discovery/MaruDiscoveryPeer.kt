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
package maru.p2p.discovery

import java.net.InetSocketAddress
import java.util.Optional
import org.apache.tuweni.bytes.Bytes
import tech.pegasys.teku.networking.p2p.discovery.DiscoveryPeer

class MaruDiscoveryPeer(
  publicKeyBytes: Bytes,
  val nodeIdBytes: Bytes,
  val addr: InetSocketAddress,
  val maruForkId: Optional<MaruForkId>,
) : DiscoveryPeer(publicKeyBytes, nodeIdBytes, addr, null, null, null) {
  override fun toString(): String = "MaruDiscoveryPeer(nodeId=$nodeIdBytes, address=$addr, maruForkId=$maruForkId)"
}
