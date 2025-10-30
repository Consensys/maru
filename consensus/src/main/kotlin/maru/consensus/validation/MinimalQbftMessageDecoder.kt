/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.consensus.validation

import maru.serialization.Deserializer
import org.apache.tuweni.bytes.Bytes
import org.hyperledger.besu.consensus.qbft.core.messagedata.QbftV1
import org.hyperledger.besu.crypto.SECPSignature
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
object MinimalQbftMessageDecoder : Deserializer<MinimalQbftMessageDecoder.QbftMessageMetadata> {
  /** Decoded QBFT message metadata */
  data class QbftMessageMetadata(
    val messageCode: Int,
    val sequenceNumber: Long,
    val roundNumber: Int,
    val signature: SECPSignature,
    val author: Address,
  )

  /**
   * Deserializer interface implementation.
   *
   * @param bytes The RLP-encoded QBFT message bytes
   * @return The decoded metadata
   */
  override fun deserialize(bytes: ByteArray): QbftMessageMetadata {
    val messageRlp = input(Bytes.wrap(bytes))
    // BesuMessageData list [code, data]
    messageRlp.enterList()
    val messageCode = messageRlp.readInt()
    val signedDataBytes = messageRlp.readBytes()
    messageRlp.leaveList()

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
      signature = signature,
      author = author,
    )
  }
}
