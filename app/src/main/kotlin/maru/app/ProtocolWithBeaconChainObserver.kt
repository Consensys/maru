/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.app

import maru.core.Protocol
import maru.core.SealedBeaconBlock
import maru.subscription.SubscriptionManager

class ProtocolWithBeaconChainObserver(
  private val delegate: Protocol,
  private val blockAdded: SubscriptionManager<SealedBeaconBlock>,
  private val subscriberId: String,
) : Protocol by delegate {
  override fun close() {
    try {
      delegate.close()
    } finally {
      blockAdded.removeSubscriber(subscriberId)
    }
  }
}
