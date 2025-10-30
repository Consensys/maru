/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.consensus.qbft

import maru.consensus.qbft.adapters.QbftBlockAdapter
import maru.consensus.qbft.adapters.QbftBlockCodecAdapter
import maru.consensus.qbft.adapters.QbftBlockchainAdapter
import maru.consensus.qbft.adapters.QbftValidatorProviderAdapter
import maru.core.ext.DataGenerators
import maru.p2p.ValidationResult.Companion.Ignore
import maru.p2p.ValidationResultCode
import org.assertj.core.api.Assertions.assertThat
import org.hyperledger.besu.consensus.common.bft.BftEventQueue
import org.hyperledger.besu.consensus.common.bft.ConsensusRoundIdentifier
import org.hyperledger.besu.consensus.common.bft.events.BftEvent
import org.hyperledger.besu.consensus.common.bft.payload.SignedData
import org.hyperledger.besu.consensus.qbft.core.messagedata.ProposalMessageData
import org.hyperledger.besu.consensus.qbft.core.messagewrappers.Proposal
import org.hyperledger.besu.consensus.qbft.core.payload.ProposalPayload
import org.hyperledger.besu.consensus.qbft.core.types.QbftMessage
import org.hyperledger.besu.consensus.qbft.core.types.QbftReceivedMessageEvent
import org.hyperledger.besu.crypto.SignatureAlgorithmFactory
import org.hyperledger.besu.datatypes.Address
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.hyperledger.besu.ethereum.p2p.rlpx.wire.MessageData as BesuMessageData

class QbftMessageValidatorTest {
  private val signatureAlgorithm = SignatureAlgorithmFactory.getInstance()
  private val keyPair = signatureAlgorithm.generateKeyPair()

  private val messageAuthor =
    org.hyperledger.besu.ethereum.core.Util
      .publicKeyToAddress(keyPair.publicKey)
  private val localAddress = Address.fromHexString("0x1234567890123456789012345678901234567890")

  private val blockChain = mock<QbftBlockchainAdapter>()
  private val validatorProvider = mock<QbftValidatorProviderAdapter>()
  private val bftEventQueue = mock<BftEventQueue>()

  private val validator =
    QbftMessageValidator(
      blockChain = blockChain,
      validatorProvider = validatorProvider,
      localAddress = localAddress,
      bftEventQueue = bftEventQueue,
    )

  @Test
  fun `should ignore old messages without adding to queue`() {
    whenever(blockChain.chainHeadBlockNumber).thenReturn(100L)

    val qbftMessage = createQbftMessage(50L)
    val result = validator.validate(qbftMessage).get()

    assertThat(result.code).isEqualTo(ValidationResultCode.IGNORE)
    assertThat(result).isInstanceOf(Ignore::class.java)
    verify(bftEventQueue, never()).add(any())
  }

  @Test
  fun `should ignore future messages and add to queue`() {
    whenever(blockChain.chainHeadBlockNumber).thenReturn(100L)

    val qbftMessage = createQbftMessage(150L)
    val result = validator.validate(qbftMessage).get()

    assertThat(result.code).isEqualTo(ValidationResultCode.IGNORE)
    val bftEventCaptor = argumentCaptor<BftEvent>()
    verify(bftEventQueue).add(bftEventCaptor.capture())
    assertThat((bftEventCaptor.firstValue as QbftReceivedMessageEvent).message).isEqualTo(qbftMessage)
  }

  @Test
  fun `should accept current message from known validator when local is validator`() {
    whenever(blockChain.chainHeadBlockNumber).thenReturn(100L)
    whenever(blockChain.chainHeadHeader).thenReturn(mock())
    whenever(validatorProvider.getValidatorsForBlock(any())).thenReturn(
      listOf(messageAuthor, localAddress),
    )

    val qbftMessage = createQbftMessage(100L)
    val result = validator.validate(qbftMessage).get()

    assertThat(result.code).isEqualTo(ValidationResultCode.ACCEPT)
    val bftEventCaptor = argumentCaptor<BftEvent>()
    verify(bftEventQueue).add(bftEventCaptor.capture())
    assertThat((bftEventCaptor.firstValue as QbftReceivedMessageEvent).message).isEqualTo(qbftMessage)
  }

  @Test
  fun `should ignore current message from unknown validator`() {
    val knownValidator = Address.fromHexString("0xABCDEF1234567890123456789012345678901234")

    whenever(blockChain.chainHeadBlockNumber).thenReturn(100L)
    whenever(blockChain.chainHeadHeader).thenReturn(mock())
    whenever(validatorProvider.getValidatorsForBlock(any())).thenReturn(
      listOf(knownValidator, localAddress), // messageAuthor is not in this list
    )

    val qbftMessage = createQbftMessage(100L)
    val result = validator.validate(qbftMessage).get()
    assertThat(result.code).isEqualTo(ValidationResultCode.IGNORE)
    verify(bftEventQueue, never()).add(any())
  }

  @Test
  fun `should ignore current message when local is not a validator`() {
    whenever(blockChain.chainHeadBlockNumber).thenReturn(100L)
    whenever(blockChain.chainHeadHeader).thenReturn(mock())
    whenever(validatorProvider.getValidatorsForBlock(any())).thenReturn(
      listOf(messageAuthor), // localAddress is not in the validator set
    )

    val qbftMessage = createQbftMessage(100L)
    val result = validator.validate(qbftMessage).get()

    assertThat(result.code).isEqualTo(ValidationResultCode.IGNORE)
    verify(bftEventQueue, never()).add(any())
  }

  @Test
  fun `should return invalid for malformed messages`() {
    val invalidQbftMessage = createInvalidQbftMessage()
    val result = validator.validate(invalidQbftMessage).get()

    assertThat(result.code).isEqualTo(ValidationResultCode.REJECT)
    verify(bftEventQueue, never()).add(any())
  }

  private fun createQbftMessage(sequenceNumber: Long): QbftMessage {
    val roundNumber = 1
    val roundIdentifier = ConsensusRoundIdentifier(sequenceNumber, roundNumber)
    val beaconBlock = DataGenerators.randomBeaconBlock(sequenceNumber.toULong())
    val qbftBlock = QbftBlockAdapter(beaconBlock)

    val proposalPayload = ProposalPayload(roundIdentifier, qbftBlock, QbftBlockCodecAdapter)
    val signature = signatureAlgorithm.sign(proposalPayload.hashForSignature(), keyPair)
    val signedPayload = SignedData.create(proposalPayload, signature)
    val proposal = Proposal(signedPayload, emptyList(), emptyList())
    val messageData = ProposalMessageData.create(proposal)
    return TestQbftMessage(messageData)
  }

  private fun createInvalidQbftMessage(): QbftMessage {
    val invalidMessageData =
      TestBesuMessageData(
        1,
        org.apache.tuweni.bytes.Bytes
          .fromHexString("0xDEADBEEF"),
      )
    return TestQbftMessage(invalidMessageData)
  }

  private class TestQbftMessage(
    private val messageData: BesuMessageData,
  ) : QbftMessage {
    override fun getData(): BesuMessageData = messageData

    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other !is TestQbftMessage) return false
      return messageData == other.messageData
    }

    override fun hashCode(): Int = messageData.hashCode()
  }

  private class TestBesuMessageData(
    private val code: Int,
    private val data: org.apache.tuweni.bytes.Bytes,
  ) : BesuMessageData {
    override fun getData(): org.apache.tuweni.bytes.Bytes = data

    override fun getSize(): Int = data.size()

    override fun getCode(): Int = code
  }
}
