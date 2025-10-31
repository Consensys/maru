/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.consensus.qbft

import org.hyperledger.besu.consensus.qbft.core.types.QbftMessage
import org.hyperledger.besu.ethereum.p2p.rlpx.wire.MessageData

data class TestQbftMessage(
  private val messageData: MessageData,
) : QbftMessage {
  override fun getData(): MessageData = messageData
}
