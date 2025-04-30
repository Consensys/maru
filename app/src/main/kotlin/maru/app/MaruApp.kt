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
package maru.app

import P2PManager
import java.time.Clock
import maru.app.config.MaruConfig
import maru.consensus.ForksSchedule
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

class MaruApp(
  val config: MaruConfig,
  beaconGenesisConfig: ForksSchedule,
  clock: Clock = Clock.systemUTC(),
) {
  private val log: Logger = LogManager.getLogger(this::class.java)

  val p2pManager = P2PManager()

  init {
//    if (config.p2pConfig == null) {
//      log.warn("P2P is disabled!")
//    }
    if (config.validator == null) {
      log.info("Maru is running in follower-only node")
    }
  }

  private val eventProducer =
    DummyConsensusProtocolBuilder.build(
      forksSchedule = beaconGenesisConfig,
      clock = clock,
      executionClientConfig = config.executionClientConfig,
      dummyConsensusOptions = config.dummyConsensusOptions!!,
    )

  fun start() {
    if (config.p2pConfig != null) {
      val p2pConfig = config.p2pConfig
      val staticPeers = p2pConfig?.staticPeers ?: emptyList()
      val privateKeyFile = p2pConfig?.privateKeyFile
      val networks = p2pConfig?.networks

      p2pManager.start(staticPeers, privateKeyFile, networks)
    }
    eventProducer.start()
    log.info("Maru is up")
  }

  fun stop() {
    p2pManager.stop()
    eventProducer.stop()
  }
}
