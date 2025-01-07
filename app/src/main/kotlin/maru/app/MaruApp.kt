package maru.app

import maru.app.config.BeaconGenesisConfig
import maru.app.config.MaruConfig
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

class MaruApp(
  private val config: MaruConfig,
  private val beaconGenesisConfig: BeaconGenesisConfig,
) {
  private val log: Logger = LogManager.getLogger(this::class.java)

  fun start() {
    if (config.p2pConfig == null) {
      log.warn("P2P is disabled!")
    }
    if (config.validator == null) {
      log.info("Maru is running in follower-only node")
    }
    log.info("Maru is up")
  }

  fun stop() {}
}
