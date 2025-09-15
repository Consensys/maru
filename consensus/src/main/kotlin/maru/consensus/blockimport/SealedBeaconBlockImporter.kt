/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.consensus.blockimport

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.flatMap
import com.github.michaelbull.result.mapError
import java.util.concurrent.atomic.AtomicBoolean
import maru.consensus.AsyncFunction
import maru.consensus.CallAndForgetFutureMultiplexer
import maru.consensus.state.StateTransition
import maru.consensus.validation.BeaconBlockValidatorFactory
import maru.consensus.validation.SealsVerifier
import maru.core.SealedBeaconBlock
import maru.database.BeaconChain
import maru.extensions.encodeHex
import maru.p2p.SealedBeaconBlockHandler
import maru.p2p.ValidationResult
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.hyperledger.besu.util.log.LogUtil
import tech.pegasys.teku.infrastructure.async.SafeFuture

// This is basically Chain of Responsibility design pattern, except it doesn't allow multiple children
// Multiplexer class was created to address that
fun interface SealedBeaconBlockImporter<T> {
  fun importBlock(sealedBeaconBlock: SealedBeaconBlock): SafeFuture<T>
}

class NewSealedBeaconBlockHandlerMultiplexer<T>(
  handlersMap: Map<String, SealedBeaconBlockHandler<*>>,
  log: Logger = LogManager.getLogger(CallAndForgetFutureMultiplexer<*>::javaClass)!!,
) : CallAndForgetFutureMultiplexer<SealedBeaconBlock>(
    handlersMap = sealedBlockHandlersToGenericHandlers(handlersMap),
    log = log,
  ),
  SealedBeaconBlockHandler<Unit> {
  companion object {
    fun sealedBlockHandlersToGenericHandlers(
      handlersMap: Map<String, SealedBeaconBlockHandler<*>>,
    ): Map<String, AsyncFunction<SealedBeaconBlock, Unit>> =
      handlersMap.mapValues { newSealedBlockHandler ->
        {
          newSealedBlockHandler.value.handleSealedBlock(it).thenApply { }
        }
      }
  }

  override fun Logger.logError(
    handlerName: String,
    input: SealedBeaconBlock,
    ex: Exception,
  ) {
    this.error(
      "New sealed block handler $handlerName failed processing" +
        "blockHash=${input.beaconBlock.beaconBlockHeader.hash}, number=${input.beaconBlock.beaconBlockHeader.number} " +
        "executionPayloadBlockNumber=${input.beaconBlock.beaconBlockBody.executionPayload.blockNumber}!",
      ex,
    )
  }

  override fun handleSealedBlock(sealedBeaconBlock: SealedBeaconBlock): SafeFuture<Unit> = handle(sealedBeaconBlock)
}

/**
 * Responsible for: transactional  and El node
 * 1. state transition of node's BeaconChain
 * 2. new block import into an EL node
 * The import is transactional, I.e. all or nothing approach
 */
class TransactionalSealedBeaconBlockImporter(
  private val beaconChain: BeaconChain,
  private val stateTransition: StateTransition,
  private val beaconBlockImporter: BeaconBlockImporter,
) : SealedBeaconBlockImporter<ValidationResult> {
  private val log: Logger = LogManager.getLogger(this.javaClass)

  override fun importBlock(sealedBeaconBlock: SealedBeaconBlock): SafeFuture<ValidationResult> {
    val updater = beaconChain.newBeaconChainUpdater()
    val clBlockNumber = sealedBeaconBlock.beaconBlock.beaconBlockHeader.number
    val elBLockNumber = sealedBeaconBlock.beaconBlock.beaconBlockBody.executionPayload.blockNumber
    try {
      log.trace(
        "Importing clBlockNumber={} elBlockNumber={}",
        clBlockNumber,
        elBLockNumber,
      )
      return stateTransition
        .processBlock(sealedBeaconBlock.beaconBlock)
        .thenApply { resultingState ->
          updater
            .putBeaconState(resultingState)
            .putSealedBeaconBlock(sealedBeaconBlock)
          updater.commit()
          log.trace(
            "Import complete clBlockNumber={} elBlockNumber={}",
            clBlockNumber,
            elBLockNumber,
          )
          resultingState
        }.thenPeek { resultingState ->
          // Block import doesn't participate in the validation
          beaconBlockImporter
            .importBlock(resultingState, sealedBeaconBlock.beaconBlock)
            .whenException { e ->
              log.error(
                "Failure importing a valid CL block! clBlockNumber={}, elBlockNumber={}",
                clBlockNumber,
                elBLockNumber,
                e,
              )
            }
        }.thenApply {
          ValidationResult.Companion.Valid as ValidationResult
        }.exceptionally { ex ->
          log.trace(
            "Import reverted clBlockNumber={} elBlockNumber={}",
            clBlockNumber,
            elBLockNumber,
          )
          updater.rollback()
          ValidationResult.Companion.Invalid(ex.message!!, ex.cause)
        }.whenComplete { _, _ ->
          updater.close()
        }
    } catch (e: Exception) {
      log.error("Block import state transition failed!: ${e.message}", e)
      return SafeFuture.failedFuture(e)
    }
  }
}

/**
 * Verifies the seal and delegates to another beaconBlockImporter
 */
class ValidatingSealedBeaconBlockImporter(
  private val sealsVerifier: SealsVerifier,
  private val beaconBlockImporter: SealedBeaconBlockImporter<ValidationResult>,
  private val beaconBlockValidatorFactory: BeaconBlockValidatorFactory,
) : SealedBeaconBlockImporter<ValidationResult> {
  companion object {
    fun Result<Unit, String>.toDomain(): ValidationResult =
      when (this) {
        is Ok -> ValidationResult.Companion.Valid
        is Err -> ValidationResult.Companion.Invalid(this.error, null)
      }
  }

  private val log = LogManager.getLogger(this.javaClass)
  private val shouldLog = AtomicBoolean(true)

  override fun importBlock(sealedBeaconBlock: SealedBeaconBlock): SafeFuture<ValidationResult> {
    try {
      val beaconBlock = sealedBeaconBlock.beaconBlock
      val beaconBlockHeader = beaconBlock.beaconBlockHeader
      LogUtil.throttledLog(
        log::info,
        "block received: clBlockNumber=${beaconBlockHeader.number} elBlockNumber=${beaconBlock.beaconBlockBody.executionPayload.blockNumber} clBlockHash=${beaconBlockHeader.hash.encodeHex()}",
        shouldLog,
        30,
      )
      val blockValidators =
        beaconBlockValidatorFactory
          .createValidatorForBlock(beaconBlockHeader)
      return sealsVerifier
        .verifySeals(sealedBeaconBlock.commitSeals, beaconBlockHeader)
        .thenComposeCombined(
          blockValidators.validateBlock(beaconBlock),
        ) { sealsVerificationResult, blockValidationResult ->
          val combinedValidationResult =
            sealsVerificationResult.flatMap { blockValidationResult.mapError { it.message } }
          when (combinedValidationResult) {
            is Ok -> {
              log.debug(
                "block validated: clBlockNumber={} elBlockNumber={} clBlockHash={}",
                beaconBlockHeader.number,
                sealedBeaconBlock.beaconBlock.beaconBlockBody.executionPayload.blockNumber,
                beaconBlockHeader.hash.encodeHex(),
              )
              beaconBlockImporter.importBlock(sealedBeaconBlock).thenApply { it }
            }

            is Err -> {
              log.error(
                "validation failed: clBlockNumber={} elBlockNumber={} clBlockHash={} error={}",
                sealedBeaconBlock.beaconBlock.beaconBlockHeader.number,
                sealedBeaconBlock.beaconBlock.beaconBlockBody.executionPayload.blockNumber,
                sealedBeaconBlock.beaconBlock.beaconBlockHeader.hash
                  .encodeHex(),
                combinedValidationResult.error,
              )
              SafeFuture.completedFuture(combinedValidationResult.toDomain())
            }
          }
        }.whenException {
          log.error(
            "exception during block import: clBlockNumber={} elBlockNumber={}  clBlockHash={} errorMessage={}",
            sealedBeaconBlock.beaconBlock.beaconBlockHeader.number,
            sealedBeaconBlock.beaconBlock.beaconBlockBody.executionPayload.blockNumber,
            sealedBeaconBlock.beaconBlock.beaconBlockHeader.hash
              .encodeHex(),
            it.message,
            it,
          )
        }
    } catch (ex: Throwable) {
      log.error(
        "exception during block import: clBlockNumber={} elBlockNumber={} clBlockHash={} errorMessage={}",
        sealedBeaconBlock.beaconBlock.beaconBlockHeader.number,
        sealedBeaconBlock.beaconBlock.beaconBlockBody.executionPayload.blockNumber,
        sealedBeaconBlock.beaconBlock.beaconBlockHeader.hash
          .encodeHex(),
        ex.message,
        ex,
      )
      throw ex
    }
  }
}
