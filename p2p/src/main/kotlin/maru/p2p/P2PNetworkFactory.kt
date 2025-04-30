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

import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multiformats.Protocol
import tech.pegasys.teku.networking.p2p.network.P2PNetwork
import tech.pegasys.teku.networking.p2p.peer.Peer

object P2PNetworkFactory {
  fun build(
    privateKey: PrivKey,
    networks: List<String>,
  ): P2PNetwork<Peer> {
    val networkBuilder = P2PNetworkBuilder()

    networkBuilder.privateKey(privateKey)

    val parsedNetworks = mutableMapOf<String, Multiaddr>()
    for (network in networks) {
      val addr = Multiaddr(network)
      when {
        addr.components.any { it.protocol == Protocol.IP4 } -> {
          parsedNetworks["ipv4"] = addr
          networkBuilder.ipv4Network(addr)
        }
        addr.components.any { it.protocol == Protocol.IP6 } -> {
          parsedNetworks["ipv6"] = addr
          networkBuilder.ipv6Network(addr)
        }
        else -> throw IllegalArgumentException("Only IPv4 and IPv6 networks are allowed.")
      }
    }

    if (parsedNetworks.isEmpty()) {
      throw IllegalArgumentException("No IPv4 or IPv6 network interface found.")
    } else if (parsedNetworks.size != networks.size) {
      throw IllegalArgumentException("One of each network type, IPv4 and IPv6, is allowed.")
    }

    return networkBuilder.build()
  }
}
