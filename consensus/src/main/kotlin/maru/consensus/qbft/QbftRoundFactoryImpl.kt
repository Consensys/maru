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
package maru.consensus.qbft

import java.util.Optional
import org.hyperledger.besu.consensus.common.bft.BftExtraDataCodec
import org.hyperledger.besu.consensus.common.bft.ConsensusRoundIdentifier
import org.hyperledger.besu.consensus.qbft.core.payload.MessageFactory
import org.hyperledger.besu.consensus.qbft.core.statemachine.PreparedCertificate
import org.hyperledger.besu.consensus.qbft.core.statemachine.QbftRound
import org.hyperledger.besu.consensus.qbft.core.statemachine.QbftRoundFactory
import org.hyperledger.besu.consensus.qbft.core.statemachine.RoundState
import org.hyperledger.besu.consensus.qbft.core.types.QbftBlockHeader
import org.hyperledger.besu.consensus.qbft.core.types.QbftFinalState
import org.hyperledger.besu.consensus.qbft.core.types.QbftMinedBlockObserver
import org.hyperledger.besu.consensus.qbft.core.types.QbftProtocolSchedule
import org.hyperledger.besu.consensus.qbft.core.validation.MessageValidator
import org.hyperledger.besu.consensus.qbft.core.validation.MessageValidatorFactory
import org.hyperledger.besu.ethereum.ProtocolContext
import org.hyperledger.besu.util.Subscribers

class RoundStateImpl(
  roundIdentifier: ConsensusRoundIdentifier,
  quorum: Int,
  validator: MessageValidator,
) : RoundState(
    roundIdentifier,
    quorum,
    validator,
  ) {
  override fun constructPreparedCertificate(): Optional<PreparedCertificate> = Optional.empty()
}

class QbftRoundFactoryImpl(
  private val finalState: QbftFinalState,
  protocolContext: ProtocolContext,
  protocolSchedule: QbftProtocolSchedule,
  minedBlockObservers: Subscribers<QbftMinedBlockObserver>,
  private val messageValidatorFactory: MessageValidatorFactory,
  messageFactory: MessageFactory,
  bftExtraDataCodec: BftExtraDataCodec,
) : QbftRoundFactory(
    /* finalState = */ finalState,
    /* protocolContext = */ protocolContext,
    /* protocolSchedule = */ protocolSchedule,
    /* minedBlockObservers = */ minedBlockObservers,
    /* messageValidatorFactory = */ messageValidatorFactory,
    /* messageFactory = */ messageFactory,
    /* bftExtraDataCodec = */ bftExtraDataCodec,
  ) {
  override fun createNewRound(
    parentHeader: QbftBlockHeader,
    round: Int,
  ): QbftRound {
    val nextBlockHeight = parentHeader.number + 1L
    val roundIdentifier = ConsensusRoundIdentifier(nextBlockHeight, round)
    val roundState =
      RoundStateImpl(
        roundIdentifier,
        finalState.quorum,
        messageValidatorFactory.createMessageValidator(roundIdentifier, parentHeader),
      )
    return this.createNewRoundWithState(parentHeader, roundState)
  }
}
