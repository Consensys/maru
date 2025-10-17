/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.p2p.messages

import maru.database.BeaconChain
import maru.p2p.RequestMessage
import maru.p2p.ResponseMessage
import maru.p2p.RpcMessageType
import maru.p2p.Version
import maru.p2p.fork.ForkPeeringManager

class StatusManager(
  private val beaconChain: BeaconChain,
  private val forkIdHashManager: ForkPeeringManager,
) {
  fun createStatusPayload(): Status {
    val latestBeaconBlockHeader = beaconChain.getLatestBeaconState().beaconBlockHeader
    return Status(
      forkIdHash = forkIdHashManager.currentForkHash(),
      latestStateRoot = latestBeaconBlockHeader.hash,
      latestBlockNumber = latestBeaconBlockHeader.number,
    )
  }

  fun createStatusResponseMessage(): ResponseMessage<Status, RpcMessageType> {
    val statusPayload = createStatusPayload()
    val statusMessage =
      ResponseMessage(
        type = RpcMessageType.STATUS,
        version = Version.V1,
        payload = statusPayload,
      )
    return statusMessage
  }

  fun createStatusRequestMessage(): RequestMessage<Status, RpcMessageType> {
    val statusPayload = createStatusPayload()
    val statusMessage =
      RequestMessage(
        type = RpcMessageType.STATUS,
        version = Version.V1,
        payload = statusPayload,
      )
    return statusMessage
  }

  fun isValidForPeering(otherStatus: Status): Boolean =
    forkIdHashManager.isValidForPeering(otherForkIdHash = otherStatus.forkIdHash)
}
