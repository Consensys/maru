/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.consensus.validation

import java.util.Optional
import maru.consensus.qbft.adapters.QbftBlockAdapter
import maru.consensus.qbft.adapters.QbftBlockCodecAdapter
import maru.core.ext.DataGenerators
import maru.p2p.topics.BesuMessageDataSerDe
import org.apache.tuweni.bytes.Bytes
import org.assertj.core.api.Assertions.assertThat
import org.hyperledger.besu.consensus.common.bft.ConsensusRoundIdentifier
import org.hyperledger.besu.consensus.common.bft.payload.SignedData
import org.hyperledger.besu.consensus.qbft.core.messagedata.CommitMessageData
import org.hyperledger.besu.consensus.qbft.core.messagedata.PrepareMessageData
import org.hyperledger.besu.consensus.qbft.core.messagedata.ProposalMessageData
import org.hyperledger.besu.consensus.qbft.core.messagedata.QbftV1
import org.hyperledger.besu.consensus.qbft.core.messagedata.RoundChangeMessageData
import org.hyperledger.besu.consensus.qbft.core.messagewrappers.Commit
import org.hyperledger.besu.consensus.qbft.core.messagewrappers.Prepare
import org.hyperledger.besu.consensus.qbft.core.messagewrappers.Proposal
import org.hyperledger.besu.consensus.qbft.core.messagewrappers.RoundChange
import org.hyperledger.besu.consensus.qbft.core.payload.CommitPayload
import org.hyperledger.besu.consensus.qbft.core.payload.PreparePayload
import org.hyperledger.besu.consensus.qbft.core.payload.ProposalPayload
import org.hyperledger.besu.consensus.qbft.core.payload.RoundChangePayload
import org.hyperledger.besu.crypto.SignatureAlgorithmFactory
import org.hyperledger.besu.datatypes.Hash
import org.junit.jupiter.api.Test

class MinimalQbftMessageDecoderTest {
  private val signatureAlgorithm = SignatureAlgorithmFactory.getInstance()
  private val messageDataSerDe = BesuMessageDataSerDe()
  private val keyPair = signatureAlgorithm.generateKeyPair()

  @Test
  fun `should decode Prepare message`() {
    val sequenceNumber = 100L
    val roundNumber = 5
    val roundIdentifier = ConsensusRoundIdentifier(sequenceNumber, roundNumber)
    val digest = Hash.hash(Bytes.of(1, 2, 3, 4))

    val preparePayload = PreparePayload(roundIdentifier, digest)
    val signature = signatureAlgorithm.sign(preparePayload.hashForSignature(), keyPair)
    val signedPayload = SignedData.create(preparePayload, signature)
    val prepare = Prepare(signedPayload)
    val messageData = PrepareMessageData.create(prepare)
    val encodedMessage = messageDataSerDe.serialize(messageData)

    val metadata = MinimalQbftMessageDecoder.deserialize(encodedMessage)
    assertThat(metadata.messageCode).isEqualTo(QbftV1.PREPARE)
    assertThat(metadata.sequenceNumber).isEqualTo(sequenceNumber)
    assertThat(metadata.roundNumber).isEqualTo(roundNumber)
    assertThat(metadata.signature).isEqualTo(signature)
    assertThat(metadata.author).isEqualTo(signedPayload.author)
  }

  @Test
  fun `should decode Commit message`() {
    val sequenceNumber = 200L
    val roundNumber = 10
    val roundIdentifier = ConsensusRoundIdentifier(sequenceNumber, roundNumber)
    val digest = Hash.hash(Bytes.of(5, 6, 7, 8))

    val commitSeal = signatureAlgorithm.sign(digest, keyPair)
    val commitPayload = CommitPayload(roundIdentifier, digest, commitSeal)
    val signature = signatureAlgorithm.sign(commitPayload.hashForSignature(), keyPair)
    val signedPayload = SignedData.create(commitPayload, signature)
    val commit = Commit(signedPayload)
    val messageData = CommitMessageData.create(commit)
    val encodedMessage = messageDataSerDe.serialize(messageData)

    val metadata = MinimalQbftMessageDecoder.deserialize(encodedMessage)
    assertThat(metadata.messageCode).isEqualTo(QbftV1.COMMIT)
    assertThat(metadata.sequenceNumber).isEqualTo(sequenceNumber)
    assertThat(metadata.roundNumber).isEqualTo(roundNumber)
    assertThat(metadata.signature).isEqualTo(signature)
    assertThat(metadata.author).isEqualTo(signedPayload.author)
  }

  @Test
  fun `should decode Proposal message`() {
    val sequenceNumber = 300L
    val roundNumber = 15
    val roundIdentifier = ConsensusRoundIdentifier(sequenceNumber, roundNumber)
    val beaconBlock = DataGenerators.randomBeaconBlock(sequenceNumber.toULong())
    val qbftBlock = QbftBlockAdapter(beaconBlock)

    val proposalPayload = ProposalPayload(roundIdentifier, qbftBlock, QbftBlockCodecAdapter)
    val signature = signatureAlgorithm.sign(proposalPayload.hashForSignature(), keyPair)
    val signedPayload = SignedData.create(proposalPayload, signature)
    val proposal = Proposal(signedPayload, emptyList(), emptyList())
    val messageData = ProposalMessageData.create(proposal)
    val encodedMessage = messageDataSerDe.serialize(messageData)

    val metadata = MinimalQbftMessageDecoder.deserialize(encodedMessage)
    assertThat(metadata.messageCode).isEqualTo(QbftV1.PROPOSAL)
    assertThat(metadata.sequenceNumber).isEqualTo(sequenceNumber)
    assertThat(metadata.roundNumber).isEqualTo(roundNumber)
    assertThat(metadata.signature).isEqualTo(signature)
    assertThat(metadata.author).isEqualTo(signedPayload.author)
  }

  @Test
  fun `should decode RoundChange message`() {
    val sequenceNumber = 400L
    val roundNumber = 20
    val roundIdentifier = ConsensusRoundIdentifier(sequenceNumber, roundNumber)
    val roundChangePayload = RoundChangePayload(roundIdentifier, Optional.empty())

    val signature = signatureAlgorithm.sign(roundChangePayload.hashForSignature(), keyPair)
    val signedPayload = SignedData.create(roundChangePayload, signature)
    val roundChange = RoundChange(signedPayload, Optional.empty(), QbftBlockCodecAdapter, emptyList())
    val messageData = RoundChangeMessageData.create(roundChange)
    val encodedMessage = messageDataSerDe.serialize(messageData)

    val metadata = MinimalQbftMessageDecoder.deserialize(encodedMessage)
    assertThat(metadata.messageCode).isEqualTo(QbftV1.ROUND_CHANGE)
    assertThat(metadata.sequenceNumber).isEqualTo(sequenceNumber)
    assertThat(metadata.roundNumber).isEqualTo(roundNumber)
    assertThat(metadata.signature).isEqualTo(signature)
    assertThat(metadata.author).isEqualTo(signedPayload.author)
  }
}
