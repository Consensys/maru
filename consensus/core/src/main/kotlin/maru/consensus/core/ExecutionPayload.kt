package maru.consensus.core

import java.math.BigInteger

/**
 * Execution Payload for the Engine API and Beacon Block
 * https://github.com/ethereum/execution-apis/blob/main/src/engine/paris.md#executionpayloadv1
 */
interface ExecutionPayload {
  val parentHash: ByteArray
  val stateRoot: ByteArray
  val receiptsRoot: ByteArray
  val logsBloom: ByteArray
  val prevRandao: ByteArray
  val blockNumber: ULong
  val gasLimit: ULong
  val gasUsed: ULong
  val timestamp: ULong
  val extraData: ByteArray
  val baseFeePerGas: BigInteger
  val blockHash: ByteArray
  val transactions: List<ByteArray>
}
