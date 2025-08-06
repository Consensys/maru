package maru.syncing.beaconchain.pipeline

import kotlin.random.Random
import maru.p2p.messages.Status

object DataGenerators {
  fun randomStatus(latestBlockNumber: ULong): Status =
    Status(
      forkIdHash = Random.nextBytes(32),
      latestStateRoot = Random.nextBytes(32),
      latestBlockNumber = latestBlockNumber,
    )
}
