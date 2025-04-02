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

import java.math.BigInteger
import java.time.Clock
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.Optional
import java.util.concurrent.Executors
import kotlin.random.Random
import kotlin.random.nextULong
import maru.config.MaruConfig
import maru.consensus.ElFork
import maru.consensus.ForkSpec
import maru.consensus.ProposerSelectorImpl
import maru.consensus.ProtocolFactory
import maru.consensus.StaticValidatorProvider
import maru.consensus.dummy.EmptyBlockValidator
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
import maru.core.BeaconBlock
import maru.core.BeaconBlockBody
import maru.core.BeaconBlockHeader
import maru.core.BeaconState
import maru.core.ExecutionPayload
import maru.core.HashUtil
import maru.core.Protocol
import maru.core.SealedBeaconBlock
import maru.core.Validator
import maru.database.BeaconChain
import maru.executionlayer.client.ExecutionLayerClient
import maru.executionlayer.client.MetadataProvider
import maru.executionlayer.client.PragueWeb3jJsonRpcExecutionLayerClient
import maru.executionlayer.manager.JsonRpcExecutionLayerManager
import maru.serialization.rlp.bodyRoot
import maru.serialization.rlp.headerHash
import maru.serialization.rlp.stateRoot
import org.apache.tuweni.bytes.Bytes32
import org.hyperledger.besu.config.BftConfigOptions
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
import org.hyperledger.besu.datatypes.Address
import org.hyperledger.besu.ethereum.core.Util
import org.hyperledger.besu.plugin.services.MetricsSystem
import org.hyperledger.besu.util.Subscribers
import tech.pegasys.teku.ethereum.executionclient.web3j.Web3JClient
import tech.pegasys.teku.ethereum.executionclient.web3j.Web3JExecutionEngineClient
import tech.pegasys.teku.ethereum.executionclient.web3j.Web3jClientBuilder
import tech.pegasys.teku.infrastructure.time.SystemTimeProvider
import org.hyperledger.besu.consensus.common.ForkSpec as BesuForkSpec
import org.hyperledger.besu.consensus.common.ForksSchedule as BesuForksSchedule

private const val MESSAGE_QUEUE_LIMIT = 1000 // TODO: Make this configurable
private val ROUND_EXPIRY = Duration.of(1, ChronoUnit.SECONDS) // TODO: Make this configurable
private const val DUPLICATE_MESSAGE_LIMIT = 100 // TODO: Make this configurable
private const val FUTURE_MESSAGE_MAX_DISTANCE = 10L // TODO: Make this configurable
private const val FUTURE_MESSAGES_LIMIT = 1000L // TODO: Make this configurable

class QbftConsensusProtocolFactory(
  private val beaconChain: BeaconChain,
  private val maruConfig: MaruConfig,
  private val metricsSystem: MetricsSystem,
  private val metadataProvider: MetadataProvider,
  private val forksSchedule: maru.consensus.ForksSchedule,
) : ProtocolFactory {
  override fun create(forkSpec: ForkSpec): Protocol {
    require(forkSpec.configuration is QbftConsensusConfig) {
      "Unexpected fork specification! ${
        forkSpec
          .configuration
      } instead of ${QbftConsensusConfig::class.simpleName}"
    }

    val executionLayerClient =
      buildExecutionEngineClient(
        maruConfig.executionClientConfig.engineApiJsonRpcEndpoint.toString(),
        forkSpec.configuration.elFork,
      )
    val jsonRpcExecutionLayerManager =
      JsonRpcExecutionLayerManager
        .create(
          executionLayerClient = executionLayerClient,
          metadataProvider = metadataProvider,
          payloadValidator = EmptyBlockValidator,
        ).get()

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
      QbftBlockCreatorFactoryImpl(jsonRpcExecutionLayerManager, proposerSelector, staticValidatorProvider, beaconChain)

    // initialise database, TODO this should be done in the main app
    initGenesisBlock(setOf(Validator(localAddress.toArrayUnsafe())))

    // TODO create besu forksSchedule from maru forksSchedule
    val besuForksSchedule = createForksSchedule(forksSchedule)

    val clock = Clock.systemUTC()
    val bftExecutors = BftExecutors.create(metricsSystem, BftExecutors.ConsensusType.QBFT)
    val bftEventQueue = BftEventQueue(MESSAGE_QUEUE_LIMIT)
    val roundTimer = RoundTimer(bftEventQueue, ROUND_EXPIRY, bftExecutors)
    val blockTimer = BlockTimer(bftEventQueue, besuForksSchedule, bftExecutors, clock)
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
        .latestBeaconBlockHeader
        .number
        .toLong()
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

    val eventMultiplexer = QbftEventMultiplexer(qbftController)
    val eventProcessor = QbftEventProcessor(bftEventQueue, eventMultiplexer)
    val eventQueueExecutor = Executors.newSingleThreadExecutor()
    return QbftConsensus(qbftController, eventProcessor, bftExecutors, eventQueueExecutor)
  }

  private fun createForksSchedule(schedule: maru.consensus.ForksSchedule): BesuForksSchedule<BftConfigOptions> {
    val forkSpecs =
      schedule.getForks().map {
        val bftConfig = createBftConfig(it)
        BesuForkSpec<BftConfigOptions>(0, bftConfig)
      }
    return BesuForksSchedule(forkSpecs)
  }

  private fun createBftConfig(spec: ForkSpec): BftConfigOptions {
    val bftConfig =
      object : BftConfigOptions {
        override fun getEpochLength(): Long = 0

        override fun getBlockPeriodSeconds(): Int = spec.blockTimeSeconds

        override fun getEmptyBlockPeriodSeconds(): Int = 0

        override fun getBlockPeriodMilliseconds(): Long = 0

        override fun getRequestTimeoutSeconds(): Int = 0

        override fun getGossipedHistoryLimit(): Int = 0

        override fun getMessageQueueLimit(): Int = 0

        override fun getDuplicateMessageLimit(): Int = 0

        override fun getFutureMessagesLimit(): Int = 0

        override fun getFutureMessagesMaxDistance(): Int = 0

        override fun getMiningBeneficiary(): Optional<Address> = Optional.empty()

        override fun getBlockRewardWei(): BigInteger = BigInteger.ZERO

        override fun asMap(): Map<String, Any> = emptyMap()
      }
    return bftConfig
  }

  private fun initGenesisBlock(validators: Set<Validator> = emptySet()) {
    try {
      beaconChain.getLatestBeaconState()
    } catch (e: Exception) {
      // TODO use state transition to create genesis state
      val updater = beaconChain.newUpdater()

      // TODO: we should have a true empty block here
      val executionPayload =
        ExecutionPayload(
          parentHash = Random.nextBytes(32),
          feeRecipient = Random.nextBytes(20),
          stateRoot = Random.nextBytes(32),
          receiptsRoot = Random.nextBytes(32),
          logsBloom = Random.nextBytes(256),
          prevRandao = Random.nextBytes(32),
          blockNumber = Random.nextULong(),
          gasLimit = Random.nextULong(),
          gasUsed = Random.nextULong(),
          timestamp = Random.nextULong(),
          extraData = Random.nextBytes(32),
          baseFeePerGas = BigInteger.valueOf(Random.nextLong(0, Long.MAX_VALUE)),
          blockHash = Random.nextBytes(32),
          transactions = emptyList(),
        )
      val beaconBlockBody = BeaconBlockBody(emptyList(), executionPayload)
      val beaconBodyRoot = HashUtil.bodyRoot(beaconBlockBody)
      val tmpExpectedNewBlockHeader =
        BeaconBlockHeader(
          number = 0UL,
          timestamp = 0UL,
          proposer = Validator(ByteArray(32)),
          parentRoot = ByteArray(32),
          stateRoot = ByteArray(32),
          bodyRoot = beaconBodyRoot,
          round = 0U,
          headerHashFunction = HashUtil::headerHash,
        )
      val genesisStateRoot =
        BeaconState(
          latestBeaconBlockHeader = tmpExpectedNewBlockHeader,
          latestBeaconBlockRoot = beaconBodyRoot,
          validators = validators,
        )
      val stateRootHash = HashUtil.stateRoot(genesisStateRoot)
      val genesisBlockHeader = tmpExpectedNewBlockHeader.copy(stateRoot = stateRootHash)
      val genesisBlock = BeaconBlock(genesisBlockHeader, beaconBlockBody)
      updater.putBeaconState(genesisStateRoot)
      updater.putSealedBeaconBlock(SealedBeaconBlock(genesisBlock, emptyList()))
      updater.commit()
    }
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
