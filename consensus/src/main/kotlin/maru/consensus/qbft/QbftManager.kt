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

import java.time.Clock
import java.time.Duration
import java.time.temporal.ChronoUnit
import maru.config.MaruConfig
import maru.consensus.ProposerSelectorImpl
import maru.consensus.StaticValidatorProvider
import maru.consensus.qbft.adapters.ProposerSelectorAdapter
import maru.consensus.qbft.adapters.QbftBlockCodecAdapter
import maru.consensus.qbft.adapters.QbftBlockInterfaceAdapter
import maru.consensus.qbft.adapters.QbftBlockchainAdapter
import maru.consensus.qbft.adapters.QbftFinalStateAdapter
import maru.consensus.qbft.adapters.QbftProtocolScheduleAdapter
import maru.consensus.qbft.adapters.QbftValidatorModeTransitionLoggerAdapter
import maru.consensus.qbft.adapters.QbftValidatorProviderAdapter
import maru.consensus.qbft.network.NoopGossiper
import maru.consensus.qbft.network.NoopValidatorMulticaster
import maru.core.Validator
import maru.database.BeaconChain
import maru.executionlayer.manager.ExecutionLayerManager
import org.apache.tuweni.bytes.Bytes32
import org.hyperledger.besu.config.BftConfigOptions
import org.hyperledger.besu.consensus.common.ForksSchedule
import org.hyperledger.besu.consensus.common.bft.BftEventQueue
import org.hyperledger.besu.consensus.common.bft.BftExecutors
import org.hyperledger.besu.consensus.common.bft.BlockTimer
import org.hyperledger.besu.consensus.common.bft.MessageTracker
import org.hyperledger.besu.consensus.common.bft.RoundTimer
import org.hyperledger.besu.consensus.common.bft.statemachine.FutureMessageBuffer
import org.hyperledger.besu.consensus.qbft.core.payload.MessageFactory
import org.hyperledger.besu.consensus.qbft.core.statemachine.QbftBlockHeightManagerFactory
import org.hyperledger.besu.consensus.qbft.core.statemachine.QbftController
import org.hyperledger.besu.consensus.qbft.core.statemachine.QbftRoundFactory
import org.hyperledger.besu.consensus.qbft.core.types.QbftMinedBlockObserver
import org.hyperledger.besu.consensus.qbft.core.validation.MessageValidatorFactory
import org.hyperledger.besu.crypto.SignatureAlgorithmFactory
import org.hyperledger.besu.cryptoservices.KeyPairSecurityModule
import org.hyperledger.besu.cryptoservices.NodeKey
import org.hyperledger.besu.ethereum.core.Util
import org.hyperledger.besu.plugin.services.MetricsSystem
import org.hyperledger.besu.util.Subscribers

private const val MESSAGE_QUEUE_LIMIT = 1000 // TODO: Make this configurable
private val ROUND_EXPIRY = Duration.of(1, ChronoUnit.SECONDS) // TODO: Make this configurable
private const val DUPLICATE_MESSAGE_LIMIT = 100 // TODO: Make this configurable
private const val FUTURE_MESSAGE_MAX_DISTANCE = 10L // TODO: Make this configurable
private const val FUTURE_MESSAGES_LIMIT = 1000L // TODO: Make this configurable

class QbftManager(
  private val beaconChain: BeaconChain,
  private val maruConfig: MaruConfig,
  private val metricsSystem: MetricsSystem,
  private val executionLayerManager: ExecutionLayerManager,
  private val forksSchedule: maru.consensus.ForksSchedule,
) {
  fun start() {
    val validatorKey = maruConfig.validator?.validatorKey ?: throw IllegalArgumentException("Validator key not found")
    val signatureAlgorithm = SignatureAlgorithmFactory.getInstance()
    val privateKey = signatureAlgorithm.createPrivateKey(Bytes32.wrap(validatorKey))
    val keyPair = signatureAlgorithm.createKeyPair(privateKey)
    val securityModule = KeyPairSecurityModule(keyPair)
    val nodeKey = NodeKey(securityModule)

    val blockChain = QbftBlockchainAdapter(beaconChain)

    val localAddress = Util.publicKeyToAddress(nodeKey.publicKey)
    val staticValidatorProvider = StaticValidatorProvider(setOf(Validator(localAddress.toArrayUnsafe())))
    val validatorProvider = QbftValidatorProviderAdapter(staticValidatorProvider)
    val proposerSelector = ProposerSelectorAdapter(ProposerSelectorImpl(beaconChain, staticValidatorProvider))
    val validatorMulticaster = NoopValidatorMulticaster()
    val qbftBlockCreatorFactory =
      QbftBlockCreatorFactoryImpl(executionLayerManager, proposerSelector, staticValidatorProvider, beaconChain)

    // TODO create besu forksSchedule from maru forksSchedule
    val forksSchedule = ForksSchedule<BftConfigOptions>(emptyList())

    val clock = Clock.systemUTC()
    val bftExecutors = BftExecutors.create(metricsSystem, BftExecutors.ConsensusType.QBFT)
    val bftEventQueue = BftEventQueue(MESSAGE_QUEUE_LIMIT)
    val roundTimer = RoundTimer(bftEventQueue, ROUND_EXPIRY, bftExecutors)
    val blockTimer = BlockTimer(bftEventQueue, forksSchedule, bftExecutors, clock)
    val finalState =
      QbftFinalStateAdapter(
        localAddress,
        nodeKey,
        validatorProvider,
        proposerSelector,
        validatorMulticaster,
        roundTimer,
        blockTimer,
        qbftBlockCreatorFactory,
        clock,
        beaconChain,
      )

    // TODO connect this to the maru NewBlockHandler
    val minedBlockObservers = Subscribers.create<QbftMinedBlockObserver>()

    val blockCodec = QbftBlockCodecAdapter()
    val blockInterface = QbftBlockInterfaceAdapter()
    val protocolSchedule = QbftProtocolScheduleAdapter()
    val messageValidatorFactory =
      MessageValidatorFactory(proposerSelector, protocolSchedule, validatorProvider, blockInterface)
    val messageFactory = MessageFactory(nodeKey, blockCodec)
    val qbftRoundFactory =
      QbftRoundFactory(
        finalState,
        blockInterface,
        protocolSchedule,
        minedBlockObservers,
        messageValidatorFactory,
        messageFactory,
      )

    val transitionLogger = QbftValidatorModeTransitionLoggerAdapter()
    val qbftBlockHeightManagerFactory =
      QbftBlockHeightManagerFactory(
        finalState,
        qbftRoundFactory,
        messageValidatorFactory,
        messageFactory,
        validatorProvider,
        transitionLogger,
      )
    val gossiper = NoopGossiper()
    val duplicateMessageTracker = MessageTracker(DUPLICATE_MESSAGE_LIMIT)
    val chainHeaderNumber =
      beaconChain
        .getLatestBeaconState()
        ?.latestBeaconBlockHeader
        ?.number
        ?.toLong()
        ?: throw IllegalArgumentException("Latest block number not found")
    val futureMessageBuffer = FutureMessageBuffer(FUTURE_MESSAGE_MAX_DISTANCE, FUTURE_MESSAGES_LIMIT, chainHeaderNumber)
    val qbftController =
      QbftController(
        blockChain,
        finalState,
        qbftBlockHeightManagerFactory,
        gossiper,
        duplicateMessageTracker,
        futureMessageBuffer,
        blockCodec,
      )
    qbftController.start()
  }
}
