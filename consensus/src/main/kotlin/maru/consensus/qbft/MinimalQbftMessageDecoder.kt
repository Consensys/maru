/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.consensus.qbft

import org.apache.tuweni.bytes.Bytes
import org.hyperledger.besu.consensus.qbft.core.messagedata.QbftV1
import org.hyperledger.besu.consensus.qbft.core.types.QbftMessage
import org.hyperledger.besu.crypto.SignatureAlgorithmFactory
import org.hyperledger.besu.datatypes.Address
import org.hyperledger.besu.datatypes.Hash
import org.hyperledger.besu.datatypes.Hash.hash
import org.hyperledger.besu.ethereum.core.Util.signatureToAddress
import org.hyperledger.besu.ethereum.rlp.BytesValueRLPOutput
import org.hyperledger.besu.ethereum.rlp.RLP.input

/**
 * Compute the hash used for signature verification, implementing the same algorithm as
 * [org.hyperledger.besu.consensus.qbft.core.payload.QbftPayload.hashForSignature].
 *
 * This is the hash of the RLP encoding of: `LIST [messageType: INT_SCALAR, encodedPayload: RAW_BYTES]`
 */
internal fun hashForSignature(
  messageType: Int,
  encodedPayload: Bytes,
): Hash {
  val out = BytesValueRLPOutput()
  out.startList()
  out.writeIntScalar(messageType)
  out.writeRaw(encodedPayload)
  out.endList()
  return hash(out.encoded())
}

/**
 * Minimal RLP decoder for QBFT messages, extracting only the roundIdentifier, signature, and author needed for
 * minimal validation. This prevents more expensive decoding of the full message with the Block or other fields
 * which can be large.
 */
object MinimalQbftMessageDecoder {
  data class QbftMessageMetadata(
    val messageCode: Int,
    val sequenceNumber: Long,
    val roundNumber: Int,
    val author: Address,
  )

  /**
   * Deserializes a QBFT message to extract metadata.
   *
   * @param qbftMessage The QBFT message to decode
   * @return The decoded metadata
   */
  fun deserialize(qbftMessage: QbftMessage): QbftMessageMetadata {
    val messageCode = qbftMessage.data.code
    val signedDataBytes = qbftMessage.data.data

    // SignedData list [payload, signature] or [[payload], signature] for PROPOSAL/ROUND_CHANGE
    val signedDataRlp = input(signedDataBytes)
    // For PROPOSAL and ROUND_CHANGE, there's an extra list wrapper
    if (messageCode == QbftV1.PROPOSAL || messageCode == QbftV1.ROUND_CHANGE) {
      signedDataRlp.enterList()
    }
    signedDataRlp.enterList()
    val payloadRlp = signedDataRlp.readAsRlp()

    // Payload list [sequenceNumber, roundNumber, ...]
    payloadRlp.enterList()
    val sequenceNumber = payloadRlp.readLongScalar()
    val roundNumber = payloadRlp.readIntScalar()
    val payloadHash = hashForSignature(messageCode, payloadRlp.raw())
    val signature = signedDataRlp.readBytes(SignatureAlgorithmFactory.getInstance()::decodeSignature)
    signedDataRlp.leaveList()

    val author = signatureToAddress(signature, payloadHash)
    return QbftMessageMetadata(
      messageCode = messageCode,
      sequenceNumber = sequenceNumber,
      roundNumber = roundNumber,
      author = author,
    )
  }
}
