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
import java.util.concurrent.Executors
import kotlin.time.toJavaDuration
import maru.config.MaruConfig
import maru.consensus.ElFork
import maru.consensus.ForkSpec
import maru.consensus.ProposerSelectorImpl
import maru.consensus.ProtocolFactory
import maru.consensus.StaticValidatorProvider
import maru.consensus.qbft.adapters.ForksScheduleAdapter
import maru.consensus.qbft.adapters.ProposerSelectorAdapter
import maru.consensus.qbft.adapters.QbftBlockCodecAdapter
import maru.consensus.qbft.adapters.QbftBlockInterfaceAdapter
import maru.consensus.qbft.adapters.QbftBlockValidatorAdapter
import maru.consensus.qbft.adapters.QbftBlockchainAdapter
import maru.consensus.qbft.adapters.QbftFinalStateAdapter
import maru.consensus.qbft.adapters.QbftProtocolScheduleAdapter
import maru.consensus.qbft.adapters.QbftValidatorModeTransitionLoggerAdapter
import maru.consensus.qbft.adapters.QbftValidatorProviderAdapter
import maru.consensus.qbft.network.NoopGossiper
import maru.consensus.qbft.network.NoopValidatorMulticaster
import maru.consensus.state.FinalizationState
import maru.consensus.state.StateTransitionImpl
import maru.consensus.validation.BlockNumberValidator
import maru.consensus.validation.BodyRootValidator
import maru.consensus.validation.CompositeBlockValidator
import maru.consensus.validation.EmptyBlockValidator
import maru.consensus.validation.ExecutionPayloadValidator
import maru.consensus.validation.ParentRootValidator
import maru.consensus.validation.ProposerValidator
import maru.consensus.validation.StateRootValidator
import maru.consensus.validation.TimestampValidator
import maru.core.BeaconBlockBody
import maru.core.BeaconBlockHeader
import maru.core.BeaconState
import maru.core.Protocol
import maru.core.Validator
import maru.database.BeaconChain
import maru.executionlayer.client.ExecutionLayerClient
import maru.executionlayer.client.MetadataProvider
import maru.executionlayer.client.PragueWeb3jJsonRpcExecutionLayerClient
import maru.executionlayer.manager.JsonRpcExecutionLayerManager
import maru.executionlayer.manager.NoopValidator
import org.apache.tuweni.bytes.Bytes
import org.apache.tuweni.bytes.Bytes32
import org.hyperledger.besu.consensus.common.bft.BftEventQueue
import org.hyperledger.besu.consensus.common.bft.BftExecutors
import org.hyperledger.besu.consensus.common.bft.BlockTimer
import org.hyperledger.besu.consensus.common.bft.ConsensusRoundIdentifier
import org.hyperledger.besu.consensus.common.bft.MessageTracker
import org.hyperledger.besu.consensus.common.bft.RoundTimer
import org.hyperledger.besu.consensus.common.bft.statemachine.FutureMessageBuffer
import org.hyperledger.besu.consensus.qbft.core.payload.MessageFactory
import org.hyperledger.besu.consensus.qbft.core.statemachine.QbftBlockHeightManagerFactory
import org.hyperledger.besu.consensus.qbft.core.statemachine.QbftController
import org.hyperledger.besu.consensus.qbft.core.statemachine.QbftRoundFactory
import org.hyperledger.besu.consensus.qbft.core.types.QbftMinedBlockObserver
import org.hyperledger.besu.consensus.qbft.core.types.QbftNewChainHead
import org.hyperledger.besu.consensus.qbft.core.validation.MessageValidatorFactory
import org.hyperledger.besu.crypto.SignatureAlgorithmFactory
import org.hyperledger.besu.cryptoservices.KeyPairSecurityModule
import org.hyperledger.besu.cryptoservices.NodeKey
import org.hyperledger.besu.datatypes.Address
import org.hyperledger.besu.ethereum.core.Util
import org.hyperledger.besu.plugin.services.MetricsSystem
import org.hyperledger.besu.util.Subscribers
import tech.pegasys.teku.ethereum.executionclient.web3j.Web3JClient
import tech.pegasys.teku.ethereum.executionclient.web3j.Web3JExecutionEngineClient
import tech.pegasys.teku.ethereum.executionclient.web3j.Web3jClientBuilder
import tech.pegasys.teku.infrastructure.time.SystemTimeProvider

class QbftProtocolFactory(
  private val beaconChain: BeaconChain,
  private val maruConfig: MaruConfig,
  private val metricsSystem: MetricsSystem,
  private val metadataProvider: MetadataProvider,
  private val finalizationStateProvider: (BeaconBlockHeader) -> FinalizationState,
  private val clock: Clock,
) : ProtocolFactory {
  override fun create(forkSpec: ForkSpec): Protocol {
    require(forkSpec.configuration is QbftConsensusConfig) {
      "Unexpected fork specification! ${
        forkSpec
          .configuration
      } instead of ${QbftConsensusConfig::class.simpleName}"
    }
    require(forkSpec.blockTimeSeconds * 1000 > maruConfig.qbftOptions.communicationMargin.inWholeMilliseconds) {
      "communicationMargin can't be more than blockTimeSeconds"
    }

    val executionLayerClient =
      buildExecutionEngineClient(
        maruConfig.executionClientConfig.engineApiJsonRpcEndpoint.toString(),
        forkSpec.configuration.elFork,
      )
    val executionLayerManager =
      JsonRpcExecutionLayerManager
        .create(
          executionLayerClient = executionLayerClient,
          metadataProvider = metadataProvider,
          payloadValidator = NoopValidator,
        ).get()

    val validatorKey =
      maruConfig.validator?.validatorKey ?: throw IllegalArgumentException("Validator key not found")
    val signatureAlgorithm = SignatureAlgorithmFactory.getInstance()
    val privateKey = signatureAlgorithm.createPrivateKey(Bytes32.wrap(validatorKey))
    val keyPair = signatureAlgorithm.createKeyPair(privateKey)
    val securityModule = KeyPairSecurityModule(keyPair)
    val nodeKey = NodeKey(securityModule)

    val blockChain = QbftBlockchainAdapter(beaconChain)

    val localAddress = Util.publicKeyToAddress(keyPair.publicKey)
    val staticValidatorProvider = StaticValidatorProvider(setOf(Validator(localAddress.toArrayUnsafe())))
    val validatorProvider = QbftValidatorProviderAdapter(staticValidatorProvider)
    val proposerSelector = ProposerSelectorImpl
    val qbftProposerSelector = ProposerSelectorAdapter(beaconChain, proposerSelector)
    val validatorMulticaster = NoopValidatorMulticaster()
    val qbftBlockCreatorFactory =
      QbftBlockCreatorFactory(
        manager = executionLayerManager,
        proposerSelector = qbftProposerSelector,
        validatorProvider = staticValidatorProvider,
        beaconChain = beaconChain,
        finalizationStateProvider = finalizationStateProvider,
        blockBuilderIdentity = Validator(localAddress.toArray()),
        eagerQbftBlockCreatorConfig =
          EagerQbftBlockCreator.Config(
            forkSpec.blockTimeSeconds,
            maruConfig.qbftOptions.communicationMargin,
          ),
        clock = clock,
      )

    val besuForksSchedule = ForksScheduleAdapter(forkSpec, maruConfig.qbftOptions)

    val clock = Clock.systemUTC()
    val bftExecutors = BftExecutors.create(metricsSystem, BftExecutors.ConsensusType.QBFT)
    val bftEventQueue = BftEventQueue(maruConfig.qbftOptions.messageQueueLimit)
    val roundTimer =
      RoundTimer(
        /* queue = */ bftEventQueue,
        /* baseExpiryPeriod = */ maruConfig.qbftOptions.roundExpiry.toJavaDuration(),
        /* bftExecutors = */ bftExecutors,
      )
    val blockTimer = BlockTimer(bftEventQueue, besuForksSchedule, bftExecutors, clock)
    val finalState =
      QbftFinalStateAdapter(
        localAddress = localAddress,
        nodeKey = nodeKey,
        validatorProvider = validatorProvider,
        proposerSelector = qbftProposerSelector,
        validatorMulticaster = validatorMulticaster,
        roundTimer = roundTimer,
        blockTimer = blockTimer,
        blockCreatorFactory = qbftBlockCreatorFactory,
        clock = clock,
        beaconChain = beaconChain,
      )

    // TODO connect this to the maru NewBlockHandler
    val minedBlockObservers = Subscribers.create<QbftMinedBlockObserver>()
    minedBlockObservers.subscribe { qbftBlock ->
      bftEventQueue.add(QbftNewChainHead(qbftBlock.header))
    }

    val finalizationStateProvider = { beaconBlockBody: BeaconBlockBody ->
      val hash = beaconBlockBody.executionPayload.blockHash
      FinalizationState(hash, hash)
    }
    val nextBlockTimestampProvider = { roundIdentifier: ConsensusRoundIdentifier ->
      val currentBlockTime = besuForksSchedule.getFork(roundIdentifier.sequenceNumber).value.blockPeriodSeconds
      (clock.millis() / 1000.0).toLong() + currentBlockTime
    }
    val shouldBuildNextBlock =
      { beaconState: BeaconState, roundIdentifier: ConsensusRoundIdentifier ->
        val nextProposerAddress =
          Address.wrap(Bytes.wrap(proposerSelector.getProposerForBlock(beaconState, roundIdentifier).get().address))
        nextProposerAddress.equals(localAddress)
      }
    val beaconBlockImporter =
      BeaconBlockImporterImpl(
        executionLayerManager = executionLayerManager,
        finalizationStateProvider = finalizationStateProvider,
        nextBlockTimestampProvider = nextBlockTimestampProvider,
        shouldBuildNextBlock = shouldBuildNextBlock,
        blockBuilderIdentity = Validator(localAddress.toArray()),
      )

    val stateTransition = StateTransitionImpl(staticValidatorProvider)
    val stateRootValidator = StateRootValidator(stateTransition)
    val bodyRootValidator = BodyRootValidator()
    val executionPayloadValidator = ExecutionPayloadValidator(executionLayerClient)
    val blockValidatorFactory = { blockHeader: BeaconBlockHeader ->
      val parentHeader =
        beaconChain.getSealedBeaconBlock(blockHeader.number - 1UL)!!.beaconBlock.beaconBlockHeader
      val compositeValidator =
        CompositeBlockValidator(
          blockValidators =
            listOf(
              stateRootValidator,
              BlockNumberValidator(parentHeader),
              TimestampValidator(parentHeader),
              ProposerValidator(proposerSelector, beaconChain),
              ParentRootValidator(parentHeader),
              bodyRootValidator,
              executionPayloadValidator,
              EmptyBlockValidator,
            ),
        )
      QbftBlockValidatorAdapter(compositeValidator)
    }

    val blockImporter = QbftBlockImportCoordinator(beaconChain, stateTransition, beaconBlockImporter)

    val blockCodec = QbftBlockCodecAdapter()
    val blockInterface = QbftBlockInterfaceAdapter()
    val protocolSchedule =
      QbftProtocolScheduleAdapter(blockImporter, blockValidatorFactory)
    val messageValidatorFactory =
      MessageValidatorFactory(qbftProposerSelector, protocolSchedule, validatorProvider, blockInterface)
    val messageFactory = MessageFactory(nodeKey, blockCodec)
    val qbftRoundFactory =
      QbftRoundFactory(
        /* finalState = */ finalState,
        /* blockInterface = */ blockInterface,
        /* protocolSchedule = */ protocolSchedule,
        /* minedBlockObservers = */ minedBlockObservers,
        /* messageValidatorFactory = */ messageValidatorFactory,
        /* messageFactory = */ messageFactory,
      )

    val transitionLogger = QbftValidatorModeTransitionLoggerAdapter()
    val qbftBlockHeightManagerFactory =
      QbftBlockHeightManagerFactory(
        /* finalState = */ finalState,
        /* roundFactory = */ qbftRoundFactory,
        /* messageValidatorFactory = */ messageValidatorFactory,
        /* messageFactory = */ messageFactory,
        /* validatorProvider = */ validatorProvider,
        /* validatorModeTransitionLogger = */ transitionLogger,
      )
    val gossiper = NoopGossiper()
    val duplicateMessageTracker = MessageTracker(maruConfig.qbftOptions.duplicateMessageLimit)
    val chainHeaderNumber =
      beaconChain
        .getLatestBeaconState()
        .latestBeaconBlockHeader
        .number
        .toLong()
    val futureMessageBuffer =
      FutureMessageBuffer(
        /* futureMessagesMaxDistance = */ maruConfig.qbftOptions.futureMessageMaxDistance,
        /* futureMessagesLimit = */ maruConfig.qbftOptions.futureMessagesLimit,
        /* chainHeight = */ chainHeaderNumber,
      )
    val qbftController =
      QbftController(
        /* blockchain = */ blockChain,
        /* finalState = */ finalState,
        /* qbftBlockHeightManagerFactory = */ qbftBlockHeightManagerFactory,
        /* gossiper = */ gossiper,
        /* duplicateMessageTracker = */ duplicateMessageTracker,
        /* futureMessageBuffer = */ futureMessageBuffer,
        /* blockEncoder = */ blockCodec,
      )

    val eventMultiplexer = QbftEventMultiplexer(qbftController)
    val eventProcessor = QbftEventProcessor(bftEventQueue, eventMultiplexer)
    val eventQueueExecutor = Executors.newSingleThreadExecutor()

    return QbftConsensus(
      qbftController = qbftController,
      eventProcessor = eventProcessor,
      bftExecutors = bftExecutors,
      eventQueueExecutor = eventQueueExecutor,
      beaconChain = beaconChain,
    )
  }

  private fun buildExecutionEngineClient(
    endpoint: String,
    elFork: ElFork,
  ): ExecutionLayerClient {
    val web3JEngineApiClient: Web3JClient =
      Web3jClientBuilder()
        .endpoint(endpoint)
        .timeout(Duration.ofMinutes(1))
        .timeProvider(SystemTimeProvider.SYSTEM_TIME_PROVIDER)
        .executionClientEventsPublisher { }
        .build()
    val web3jExecutionLayerClient = Web3JExecutionEngineClient(web3JEngineApiClient)
    return when (elFork) {
      ElFork.Prague -> PragueWeb3jJsonRpcExecutionLayerClient(web3jExecutionLayerClient)
    }
  }
}
