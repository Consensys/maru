/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.consensus.qbft.adapters

import io.libp2p.pubsub.NoPeersForOutboundMessageException
import maru.p2p.P2PNetwork
import org.apache.logging.log4j.LogManager
import org.hyperledger.besu.consensus.common.bft.network.ValidatorMulticaster
import org.hyperledger.besu.consensus.qbft.core.messagedata.QbftV1
import org.hyperledger.besu.datatypes.Address
import org.hyperledger.besu.ethereum.rlp.RLP
import org.hyperledger.besu.ethereum.p2p.rlpx.wire.MessageData as BesuMessageData

/**
 * Adapter that implements the Hyperledger Besu ValidatorMulticaster interface and delegates to a P2PNetwork.
 *
 * This adapter is used by the QBFT consensus protocol to send messages to validators.
 *
 * The send is fire-and-forget: BftProcessor's Thread-14 must not block on network I/O or it
 * serialises the entire QBFT pipeline (every hop waits for the previous send to complete).
 * Errors are logged but not propagated; QBFT is resilient to individual message delivery failures.
 */
class P2PValidatorMulticaster(
  private val p2pNetwork: P2PNetwork,
) : ValidatorMulticaster {
  private val log = LogManager.getLogger(this.javaClass)

  /**
   * Optional observer called just before a QBFT message is submitted to the P2P network for broadcast.
   * Parameters: (msgCode: Int, sequenceNumber: Long).
   * Intended for benchmarking — the callback implementer captures timestamps themselves.
   */
  var onMessageSent: ((msgCode: Int, sequenceNumber: Long) -> Unit)? = null

  /**
   * Send a message to all connected validators.
   *
   * @param message The message to send.
   */
  override fun send(message: BesuMessageData) {
    onMessageSent?.let { callback ->
      extractCodeAndSequence(message)?.let { (code, seq) -> callback(code, seq) }
    }
    p2pNetwork
      .broadcastMessage(message.toDomain())
      .whenException { e ->
        if (e !is NoPeersForOutboundMessageException) {
          log.error("Failed to broadcast QBFT message", e)
        }
      }
  }

  /**
   * Send a message to all connected validators except those in the denyList.
   *
   * @param message The message to send.
   * @param denyList This becomes irrelevant because it's a broadcasting under the hood, but needs to be there for the
   * completeness of the interface
   */
  override fun send(
    message: BesuMessageData,
    denyList: Collection<Address>,
  ) {
    send(message)
  }

  /**
   * Lightweight extraction of QBFT message code and sequence number from RLP.
   * Same structure as [maru.consensus.qbft.MinimalQbftMessageDecoder.deserialize] but
   * skips signature recovery — only reads the first two scalars from the payload.
   */
  private fun extractCodeAndSequence(message: BesuMessageData): Pair<Int, Long>? =
    try {
      val messageCode = message.code
      val rlp = RLP.input(message.data)
      if (messageCode == QbftV1.PROPOSAL || messageCode == QbftV1.ROUND_CHANGE) {
        rlp.enterList()
      }
      rlp.enterList()
      val payloadRlp = rlp.readAsRlp()
      payloadRlp.enterList()
      val sequenceNumber = payloadRlp.readLongScalar()
      Pair(messageCode, sequenceNumber)
    } catch (_: Exception) {
      null
    }
}
