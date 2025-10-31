/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.consensus.qbft

import maru.consensus.qbft.adapters.QbftBlockchainAdapter
import maru.consensus.qbft.adapters.QbftValidatorProviderAdapter
import maru.consensus.qbft.adapters.toQbftReceivedMessageEvent
import maru.p2p.ValidationResult
import maru.p2p.ValidationResult.Companion.Ignore
import maru.p2p.ValidationResult.Companion.Invalid
import maru.p2p.ValidationResult.Companion.Valid
import org.hyperledger.besu.consensus.common.bft.BftEventQueue
import org.hyperledger.besu.consensus.qbft.core.types.QbftMessage
import org.hyperledger.besu.datatypes.Address
import tech.pegasys.teku.infrastructure.async.SafeFuture

/**
 * Processes QBFT messages received from the P2P network by applying a light validation, adding them to the
 * event queue if they are valid and returning the validation result to gossip if they are a current message.
 *
 * This mirrors the logic in Besu QbftController.processMessage but adapted for LibP2P message handling.
 *
 * Validation rules:
 * - Old messages (sequence < chainHeight): Ignored and not added to the event queue
 * - Future messages (sequence > chainHeight): Added to the event queue but not gossiped
 * - Current messages (sequence == chainHeight): Validated for author and local validator status and gossiped if valid
 */
class QbftMessageProcessor(
  private val blockChain: QbftBlockchainAdapter,
  private val validatorProvider: QbftValidatorProviderAdapter,
  private val localAddress: Address,
  private val bftEventQueue: BftEventQueue,
) {
  /**
   * Validates a QBFT message and determines whether it should be gossiped.
   *
   * @param qbftMessage The QBFT message to validate
   * @return A future containing the validation result
   */
  fun handleMessage(qbftMessage: QbftMessage): SafeFuture<ValidationResult> =
    try {
      val metadata = MinimalQbftMessageDecoder.deserialize(qbftMessage)
      val result = processMessage(qbftMessage, metadata)
      SafeFuture.completedFuture(result)
    } catch (e: Exception) {
      SafeFuture.completedFuture(Invalid("Failed to decode or validate message: ${e.message}"))
    }

  private fun processMessage(
    qbftMessage: QbftMessage,
    metadata: MinimalQbftMessageDecoder.QbftMessageMetadata,
  ): ValidationResult {
    if (isMsgForCurrentHeight(metadata.sequenceNumber)) {
      return if (isMsgFromKnownValidator(metadata.author) && isLocalNodeValidator()) {
        bftEventQueue.add(qbftMessage.toQbftReceivedMessageEvent())
        Valid
      } else {
        Ignore("Not from known validator or not a local validator")
      }
    } else if (isMsgForFutureChainHeight(metadata.sequenceNumber)) {
      bftEventQueue.add(qbftMessage.toQbftReceivedMessageEvent())
      return Ignore("Future message")
    } else {
      return Ignore("Old message: sequence ${metadata.sequenceNumber} < height ${blockChain.chainHeadBlockNumber}")
    }
  }

  private fun isMsgFromKnownValidator(messageAuthor: Address): Boolean {
    val validators = validatorProvider.getValidatorsForBlock(blockChain.chainHeadHeader)
    return validators.contains(messageAuthor)
  }

  private fun isMsgForCurrentHeight(sequenceNumber: Long): Boolean = sequenceNumber == blockChain.chainHeadBlockNumber

  private fun isMsgForFutureChainHeight(sequenceNumber: Long): Boolean =
    sequenceNumber > blockChain.chainHeadBlockNumber

  private fun isLocalNodeValidator(): Boolean {
    val validators = validatorProvider.getValidatorsForBlock(blockChain.chainHeadHeader)
    return validators.contains(localAddress)
  }
}
