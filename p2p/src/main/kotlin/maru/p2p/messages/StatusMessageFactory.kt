/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.p2p.messages

import java.time.Clock
import maru.consensus.ForkIdHashProvider
import maru.database.BeaconChain
import maru.p2p.Message
import maru.p2p.RpcMessageType
import maru.p2p.Version
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.apache.tuweni.bytes.Bytes

class StatusMessageFactory(
  private val beaconChain: BeaconChain,
  forkIdHashProvider: ForkIdHashProvider,
  private val clock: Clock = Clock.systemUTC(),
) {
  private val log: Logger = LogManager.getLogger(this.javaClass)

  private var forkIdHash: ByteArray = forkIdHashProvider.currentForkIdHash()

  private var lastUpdate: LastUpdate = LastUpdate(0L, ByteArray(0))

  fun createStatusMessage(): Message<Status, RpcMessageType> {
    val latestBeaconBlockHeader = beaconChain.getLatestBeaconState().beaconBlockHeader
    val statusPayload = Status(forkIdHash = forkIdHash, latestBeaconBlockHeader.hash, latestBeaconBlockHeader.number)
    val statusMessage =
      Message(
        type = RpcMessageType.STATUS,
        version = Version.V1,
        payload = statusPayload,
      )
    return statusMessage
  }

  fun updateForkIdHash(forkIdHash: ByteArray) {
    log.info(
      "Updated forkIdHash from ${Bytes.wrap(
        this.forkIdHash,
      ).toHexString()} to ${Bytes.wrap(forkIdHash).toHexString()} at ${clock.instant().toEpochMilli()}",
    )
    lastUpdate = LastUpdate(clock.instant().toEpochMilli(), this.forkIdHash)
    this.forkIdHash = forkIdHash
  }

  // returns true if the forkIdHash passed in was valid 5 seconds ago
  fun forkIdHasJustChangedFrom(forkId: ByteArray): Boolean =
    forkId.contentEquals(lastUpdate.forkIdHash) && clock.instant().toEpochMilli() - lastUpdate.timestamp < 5000

  data class LastUpdate(
    val timestamp: Long,
    val forkIdHash: ByteArray,
  )
}
