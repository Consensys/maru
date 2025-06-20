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

import java.util.concurrent.TimeUnit
import maru.config.P2P
import maru.p2p.discovery.MaruDiscoveryService
import org.apache.tuweni.bytes.Bytes
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.SAME_THREAD)
class DiscoveryTest {
  companion object {
    private const val IPV4 = "127.0.0.1"

    private const val PORT1 = 9234u
    private const val PORT2 = 9235u
    private const val PORT3 = 9236u
    private const val PORT4 = 9237u
    private const val PORT5 = 9238u
    private const val PORT6 = 9239u

    private const val PRIVATE_KEY1: String =
      "0x12c0b113e2b0c37388e2b484112e13f05c92c4471e3ee1dfaa368fa5045325b2"
    private const val PRIVATE_KEY2: String =
      "0xf3d2fffa99dc8906823866d96316492ebf7a8478713a89a58b7385af85b088a1"
    private const val PRIVATE_KEY3: String =
      "0x4437acb8e84bc346f7640f239da84abe99bc6f97b7855f204e34688d2977fd57"

    private val key1 = Bytes.fromHexString(PRIVATE_KEY1).toArray()
    private val key2 = Bytes.fromHexString(PRIVATE_KEY2).toArray()
    private val key3 = Bytes.fromHexString(PRIVATE_KEY3).toArray()
  }

  @Test
  fun `discovery finds nodes`() {
    val bootnode =
      MaruDiscoveryService(
        privateKeyBytes = key1,
        p2pConfig =
          P2P(
            ipAddress = IPV4,
            port = PORT1,
            discoveryPort = PORT2,
            bootnodes = emptyList(),
          ),
      )

    val enrString = getBootnodeEnrString(key1, IPV4, PORT2.toInt(), PORT1.toInt())

    val discoveryService2 =
      MaruDiscoveryService(
        privateKeyBytes = key2,
        p2pConfig =
          P2P(
            ipAddress = IPV4,
            port = PORT3,
            discoveryPort = PORT4,
            bootnodes = listOf(enrString),
          ),
      )

    val discoveryService3 =
      MaruDiscoveryService(
        privateKeyBytes = key3,
        p2pConfig =
          P2P(
            ipAddress = IPV4,
            port = PORT5,
            discoveryPort = PORT6,
            bootnodes = listOf(enrString),
          ),
      )

    try {
      bootnode.start()
      discoveryService2.start()
      discoveryService3.start()

      Awaitility
        .await()
        .timeout(10, TimeUnit.SECONDS)
        .untilAsserted {
          val get = discoveryService2.searchForPeers().get()
          assertThat(
            get
              .stream()
              .filter { it.nodeIdBytes == discoveryService3.getLocalNodeRecord().nodeId }
              .count(),
          ).isGreaterThan(0L)
        }
      Awaitility
        .await()
        .timeout(10, TimeUnit.SECONDS)
        .untilAsserted {
          val get = discoveryService3.searchForPeers().get()
          assertThat(
            get
              .stream()
              .filter { it.nodeIdBytes == discoveryService2.getLocalNodeRecord().nodeId }
              .count(),
          ).isGreaterThan(0L)
        }
      Awaitility
        .await()
        .timeout(10, TimeUnit.SECONDS)
        .untilAsserted {
          val get = bootnode.searchForPeers().get()
          assertThat(
            get
              .stream()
              .filter { it.nodeIdBytes == discoveryService2.getLocalNodeRecord().nodeId }
              .count(),
          ).isGreaterThan(0L)
        }
      Awaitility
        .await()
        .timeout(10, TimeUnit.SECONDS)
        .untilAsserted {
          val get = bootnode.searchForPeers().get()
          assertThat(
            get
              .stream()
              .filter { it.nodeIdBytes == discoveryService3.getLocalNodeRecord().nodeId }
              .count(),
          ).isGreaterThan(0L)
        }
    } finally {
      bootnode.stop()
      discoveryService2.stop()
      discoveryService3.stop()
    }
  }
}
