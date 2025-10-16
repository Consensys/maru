/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.p2p.topics

import maru.serialization.rlp.RLPSerDe
import org.hyperledger.besu.consensus.qbft.core.types.QbftMessage
import org.hyperledger.besu.ethereum.p2p.rlpx.wire.MessageData
import org.hyperledger.besu.ethereum.rlp.RLPInput
import org.hyperledger.besu.ethereum.rlp.RLPOutput

class QbftMessageSerDe : RLPSerDe<QbftMessage> {
  private val messageDataSerDe = MessageDataSerDe()

  override fun writeTo(
    value: QbftMessage,
    rlpOutput: RLPOutput,
  ) {
    messageDataSerDe.writeTo(value.data, rlpOutput)
  }

  override fun readFrom(rlpInput: RLPInput): QbftMessage {
    val messageData = messageDataSerDe.readFrom(rlpInput)
    return MaruQbftMessage(messageData)
  }

  internal class MaruQbftMessage(
    private val messageData: MessageData,
  ) : QbftMessage {
    override fun getData(): MessageData = messageData

    override fun toString(): String = "QbftMessage(data=$messageData)"
  }
}
