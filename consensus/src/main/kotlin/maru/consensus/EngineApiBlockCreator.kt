/*
   Copyright 2025 Consensys Software Inc.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */
package maru.consensus

import java.util.Optional
import maru.consensus.dummy.DummyConsensusState
import maru.core.executionlayer.manager.ExecutionLayerManager
import org.apache.logging.log4j.LogManager
import org.apache.tuweni.bytes.Bytes
import org.apache.tuweni.bytes.Bytes32
import org.hyperledger.besu.datatypes.Address
import org.hyperledger.besu.datatypes.BlobGas
import org.hyperledger.besu.datatypes.Hash
import org.hyperledger.besu.datatypes.Wei
import org.hyperledger.besu.ethereum.blockcreation.BlockCreator
import org.hyperledger.besu.ethereum.core.Block
import org.hyperledger.besu.ethereum.core.BlockBody
import org.hyperledger.besu.ethereum.core.BlockHeader
import org.hyperledger.besu.ethereum.core.BlockHeaderFunctions
import org.hyperledger.besu.ethereum.core.Difficulty
import org.hyperledger.besu.ethereum.core.Transaction
import org.hyperledger.besu.ethereum.mainnet.BodyValidation
import org.hyperledger.besu.ethereum.rlp.RLP
import org.hyperledger.besu.ethereum.rlp.RLPInput
import org.hyperledger.besu.evm.log.LogsBloomFilter
import tech.pegasys.teku.ethereum.executionclient.schema.ExecutionPayloadV3

/**
 * Responsible for block creation with Engine API. createEmptyWithdrawalsBlock is the only available signature, the
 * rest of the methods delegate to it
 * Even though it implements BlockCreator, this interface doesn't really fit Engine API flow due to its asynchronycity
 */
class EngineApiBlockCreator(
  private val manager: ExecutionLayerManager,
  private val state: DummyConsensusState,
  private val blockHeaderFunctions: BlockHeaderFunctions,
) : BlockCreator {
  private val log = LogManager.getLogger(EngineApiBlockCreator::class.java)

  override fun createBlock(
    timestamp: Long,
    parentHeader: BlockHeader,
  ): BlockCreator.BlockCreationResult {
    return createEmptyWithdrawalsBlock(timestamp, parentHeader)
  }

  override fun createBlock(
    transactions: MutableList<Transaction>,
    ommers: MutableList<BlockHeader>,
    timestamp: Long,
    parentHeader: BlockHeader,
  ): BlockCreator.BlockCreationResult {
    return createEmptyWithdrawalsBlock(timestamp, parentHeader)
  }

  override fun createBlock(
    maybeTransactions: Optional<MutableList<Transaction>>,
    maybeOmmers: Optional<MutableList<BlockHeader>>,
    timestamp: Long,
    parentHeader: BlockHeader,
  ): BlockCreator.BlockCreationResult {
    return createEmptyWithdrawalsBlock(timestamp, parentHeader)
  }

  override fun createEmptyWithdrawalsBlock(
    timestamp: Long,
    parentHeader: BlockHeader,
  ): BlockCreator.BlockCreationResult {
    val blockBuildingResult = manager.finishBlockBuilding().get()
    val newHeadHash = blockBuildingResult.payloadStatus.asInternalExecutionPayload().latestValidHash.get()
      .toArray()
    // Mind the atomicity of finalization updates
    val finalizationState = state.finalizationState
    manager.setHeadAndStartBlockBuilding(
      newHeadHash,
      finalizationState.safeBlockHash,
      finalizationState.finalizedBlockHash
    )
      .thenApply {
        state.updateLatestStatus(newHeadHash)
      }
      .whenException {
        log.error("Error while initiating block building!", it)
      }

    val block = mapExecutionPayloadV3ToBlock(blockBuildingResult.executionPayload)
    // This return type doesn't fit this case well so stubbing it with dummy values for now
    return BlockCreator.BlockCreationResult(block, null, null)
  }

  private fun mapExecutionPayloadV3ToBlock(payload: ExecutionPayloadV3): Block {
    val transactions = payload.transactions.map { tx ->
      val rlpInput: RLPInput = RLP.input(tx)
      Transaction.readFrom(rlpInput)
    }
    val blockHeader = BlockHeader(
      /* parentHash = */ Hash.wrap(payload.parentHash),
      /* ommersHash = */ Hash.EMPTY_LIST_HASH,
      /* coinbase = */ Address.wrap(payload.feeRecipient.wrappedBytes),
      /* stateRoot = */ Hash.wrap(payload.stateRoot),
      /* transactionsRoot = */ BodyValidation.transactionsRoot(transactions),
      /* receiptsRoot = */ Hash.wrap(payload.receiptsRoot),
      /* logsBloom = */ LogsBloomFilter(payload.logsBloom),
      /* difficulty = */ Difficulty.ZERO,
      /* number = */ payload.blockNumber.longValue(),
      /* gasLimit = */ payload.gasLimit.longValue(),
      /* gasUsed = */ payload.gasUsed.longValue(),
      /* timestamp = */ payload.timestamp.longValue(),
      /* extraData = */ Bytes.fromHexString(payload.extraData.toHexString()),
      /* baseFee = */ Wei.of(payload.baseFeePerGas),
      /* mixHashOrPrevRandao = */ Bytes32.wrap(payload.prevRandao),
      /* nonce = */ 0,
      /* withdrawalsRoot = */ Hash.EMPTY_TRIE_HASH,
      /* blobGasUsed = */ 0,
      /* excessBlobGas = */ BlobGas.ZERO,
      /* parentBeaconBlockRoot = */ Bytes32.ZERO, // Should be an actual beacon block root
      /* requestsRoot = */ Hash.EMPTY, // Should be actual requests
      /* blockHeaderFunctions = */ blockHeaderFunctions
    )

    val blockBody = BlockBody(transactions, listOf())

    // Create the block
    return Block(blockHeader, blockBody)
  }
}
