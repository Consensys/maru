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
import maru.consensus.validation.MinimalQbftMessageDecoder
import maru.p2p.ValidationResult
import maru.p2p.ValidationResult.Companion.Ignore
import maru.p2p.ValidationResult.Companion.Invalid
import maru.p2p.ValidationResult.Companion.Valid
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.hyperledger.besu.consensus.common.bft.BftEventQueue
import org.hyperledger.besu.consensus.qbft.core.types.QbftMessage
import org.hyperledger.besu.datatypes.Address
import tech.pegasys.teku.infrastructure.async.SafeFuture

/**
 * Validates QBFT messages received from the P2P network before they are added to the event queue.
 *
 * Validation rules:
 * - Old messages (sequence < chainHeight): Ignored and not added to queue
 * - Future messages (sequence > chainHeight): Added to queue but not gossiped
 * - Current messages (sequence == chainHeight): Validated for author and local validator status
 */
class QbftMessageValidator(
  private val blockChain: QbftBlockchainAdapter,
  private val validatorProvider: QbftValidatorProviderAdapter,
  private val localAddress: Address,
  private val bftEventQueue: BftEventQueue,
) {
  private val log: Logger = LogManager.getLogger(this.javaClass)

  /**
   * Validates a QBFT message and determines whether it should be gossiped.
   *
   * @param qbftMessage The QBFT message to validate
   * @return A future containing the validation result
   */
  fun validate(qbftMessage: QbftMessage): SafeFuture<ValidationResult> =
    try {
      log.trace("Validating QBFT message")
      val metadata = decodeMessage(qbftMessage)
      val currentChainHeight = blockChain.chainHeadBlockNumber

      log.trace(
        "Message metadata: sequenceNumber={}, author={}, currentChainHeight={}",
        metadata.sequenceNumber,
        metadata.author,
        currentChainHeight,
      )

      val result =
        when {
          isOldMessage(metadata.sequenceNumber, currentChainHeight) ->
            handleOldMessage(metadata.sequenceNumber, currentChainHeight)
          isFutureMessage(metadata.sequenceNumber, currentChainHeight) ->
            handleFutureMessage(qbftMessage, metadata.sequenceNumber)
          else -> handleCurrentMessage(qbftMessage, metadata.author, metadata.sequenceNumber)
        }

      log.trace("Validation result: {}", result)
      SafeFuture.completedFuture(result)
    } catch (e: Exception) {
      log.trace("Failed to decode or validate message", e)
      SafeFuture.completedFuture(Invalid("Failed to decode or validate message: ${e.message}"))
    }

  private fun decodeMessage(qbftMessage: QbftMessage): MinimalQbftMessageDecoder.QbftMessageMetadata =
    MinimalQbftMessageDecoder.deserialize(qbftMessage)

  private fun isOldMessage(
    sequenceNumber: Long,
    currentChainHeight: Long,
  ) = sequenceNumber < currentChainHeight

  private fun isFutureMessage(
    sequenceNumber: Long,
    currentChainHeight: Long,
  ) = sequenceNumber > currentChainHeight

  private fun handleOldMessage(
    sequenceNumber: Long,
    currentChainHeight: Long,
  ): ValidationResult {
    log.trace("Discarding old message: sequence={} < chainHeight={}", sequenceNumber, currentChainHeight)
    return Ignore("Old message: sequence $sequenceNumber < height $currentChainHeight")
  }

  private fun handleFutureMessage(
    qbftMessage: QbftMessage,
    sequenceNumber: Long,
  ): ValidationResult {
    log.trace("Buffering future message: sequence={}", sequenceNumber)
    bftEventQueue.add(qbftMessage.toQbftReceivedMessageEvent())
    return Ignore("Future message")
  }

  private fun handleCurrentMessage(
    qbftMessage: QbftMessage,
    messageAuthor: Address,
    sequenceNumber: Long,
  ): ValidationResult {
    log.trace("Validating current message: sequence={}, author={}", sequenceNumber, messageAuthor)

    val validators = validatorProvider.getValidatorsForBlock(blockChain.chainHeadHeader)
    log.trace("Current validators: {}", validators)

    return if (isValidCurrentMessage(messageAuthor, validators)) {
      log.trace("Message is valid, adding to queue and gossiping")
      bftEventQueue.add(qbftMessage.toQbftReceivedMessageEvent())
      Valid
    } else {
      log.trace(
        "Message validation failed: isFromKnownValidator={}, isLocalValidator={}",
        validators.contains(messageAuthor),
        validators.contains(localAddress),
      )
      Ignore("Not from known validator or not a local validator")
    }
  }

  private fun isValidCurrentMessage(
    messageAuthor: Address,
    validators: Collection<Address>,
  ): Boolean {
    val isFromKnownValidator = validators.contains(messageAuthor)
    val isLocalValidator = validators.contains(localAddress)
    return isFromKnownValidator && isLocalValidator
  }
}
