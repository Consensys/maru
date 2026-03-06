/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.consensus.qbft

import java.time.Clock
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds
import maru.config.QbftConfig
import maru.consensus.ForkSpec
import maru.consensus.ForksSchedule
import maru.consensus.NextBlockTimestampProvider
import maru.consensus.PrevRandaoProvider
import maru.consensus.PrevRandaoProviderImpl
import maru.consensus.ProtocolFactory
import maru.consensus.QbftConsensusConfig
import maru.consensus.StaticValidatorProvider
import maru.consensus.blockimport.BlockBuildingBeaconBlockImporter
import maru.consensus.blockimport.SealedBeaconBlockImporter
import maru.consensus.blockimport.TransactionalSealedBeaconBlockImporter
import maru.consensus.qbft.adapters.ForksScheduleAdapter
import maru.consensus.qbft.adapters.P2PValidatorMulticaster
import maru.consensus.qbft.adapters.ProposerSelectorAdapter
import maru.consensus.qbft.adapters.QbftBlockCodecAdapter
import maru.consensus.qbft.adapters.QbftBlockHeaderAdapter
import maru.consensus.qbft.adapters.QbftBlockImporterAdapter
import maru.consensus.qbft.adapters.QbftBlockInterfaceAdapter
import maru.consensus.qbft.adapters.QbftBlockchainAdapter
import maru.consensus.qbft.adapters.QbftFinalStateAdapter
import maru.consensus.qbft.adapters.QbftProtocolScheduleAdapter
import maru.consensus.qbft.adapters.QbftValidatorModeTransitionLoggerAdapter
import maru.consensus.qbft.adapters.QbftValidatorProviderAdapter
import maru.consensus.qbft.adapters.toSealedBeaconBlock
import maru.consensus.state.FinalizationProvider
import maru.consensus.state.StateTransition
import maru.consensus.state.StateTransitionImpl
import maru.consensus.validation.BeaconBlockValidatorFactoryImpl
import maru.core.BeaconState
import maru.core.Protocol
import maru.core.Validator
import maru.crypto.Hashing
import maru.crypto.SecpCrypto
import maru.crypto.Signing
import maru.database.BeaconChain
import maru.executionlayer.manager.ExecutionLayerManager
import maru.p2p.P2PNetwork
import maru.p2p.SealedBeaconBlockHandler
import maru.p2p.ValidationResult
import org.apache.logging.log4j.LogManager
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
import org.hyperledger.besu.consensus.qbft.core.types.QbftMessage
import org.hyperledger.besu.consensus.qbft.core.types.QbftMinedBlockObserver
import org.hyperledger.besu.consensus.qbft.core.types.QbftNewChainHead
import org.hyperledger.besu.consensus.qbft.core.validation.MessageValidatorFactory
import org.hyperledger.besu.cryptoservices.KeyPairSecurityModule
import org.hyperledger.besu.cryptoservices.NodeKey
import org.hyperledger.besu.ethereum.core.Util
import org.hyperledger.besu.plugin.services.MetricsSystem
import org.hyperledger.besu.util.Subscribers

class QbftValidatorFactory(
  private val beaconChain: BeaconChain,
  private val privateKeyBytes: ByteArray,
  private val qbftOptions: QbftConfig,
  private val metricsSystem: MetricsSystem,
  private val finalizationStateProvider: FinalizationProvider,
  private val nextBlockTimestampProvider: NextBlockTimestampProvider,
  private val blockMinedHandler: SealedBeaconBlockHandler<*>,
  private val executionLayerManager: ExecutionLayerManager,
  private val clock: Clock,
  private val p2PNetwork: P2PNetwork,
  private val allowEmptyBlocks: Boolean,
  private val forksSchedule: ForksSchedule,
  private val payloadValidationEnabled: Boolean,
  /** Optional: called when BLOCK_TIMER_EXPIRY fires. See [QbftEventMultiplexer.onBlockTimerFired]. */
  private val onBlockTimerFired: ((blockNumber: Long, wallClockMs: Long) -> Unit)? = null,
) : ProtocolFactory {
  private val log = LogManager.getLogger(QbftValidatorFactory::class.java)

  override fun create(forkSpec: ForkSpec): Protocol {
    val protocolConfig = forkSpec.configuration as QbftConsensusConfig
    val signatureAlgorithm = SecpCrypto.signatureAlgorithm
    val privateKey = signatureAlgorithm.createPrivateKey(Bytes32.wrap(privateKeyBytes))
    val keyPair = signatureAlgorithm.createKeyPair(privateKey)
    val securityModule = KeyPairSecurityModule(keyPair)
    val nodeKey = NodeKey(securityModule)
    val blockChain = QbftBlockchainAdapter(beaconChain)

    val localAddress = Util.publicKeyToAddress(keyPair.publicKey)
    val qbftProposerSelector = ProposerSelectorAdapter(beaconChain, ProposerSelectorImpl)

    val validatorProvider = StaticValidatorProvider(protocolConfig.validatorSet)
    val stateTransition = StateTransitionImpl(validatorProvider)
    val proposerSelector = ProposerSelectorImpl
    val besuValidatorProvider = QbftValidatorProviderAdapter(validatorProvider)
    val localValidator = Validator(localAddress.toArray())
    val prevRandaoProvider =
      PrevRandaoProviderImpl(
        signer = Signing.ULongSigner(nodeKey),
        hasher = Hashing::keccak,
      )
    val sealedBeaconBlockImporter =
      createSealedBeaconBlockImporter(
        executionLayerManager = executionLayerManager,
        beaconChain = beaconChain,
        stateTransition = stateTransition,
        finalizationStateProvider = finalizationStateProvider,
        prevRandaoProvider = prevRandaoProvider,
        feeRecipient = qbftOptions.feeRecipient,
        localValidator = localValidator,
        proposerSelector = proposerSelector,
      )

    val qbftBlockCreatorFactory =
      QbftBlockCreatorFactory(
        manager = executionLayerManager,
        proposerSelector = qbftProposerSelector,
        validatorProvider = validatorProvider,
        beaconChain = beaconChain,
      )

    val besuForksSchedule = ForksScheduleAdapter(forkSpec, qbftOptions)

    val bftExecutors = BftExecutors.create(metricsSystem, BftExecutors.ConsensusType.QBFT)
    val bftEventQueue = BftEventQueue(qbftOptions.messageQueueLimit)
    val roundExpiry = qbftOptions.roundExpiry ?: forkSpec.blockTimeSeconds.toInt().seconds
    val roundTimeExpiryCalculator =
      if (protocolConfig.validatorSet.size == 1) {
        ConstantRoundTimeExpiryCalculator((roundExpiry))
      } else {
        LinearRoundTimeExpiryCalculator(roundExpiry, qbftOptions.roundExpiryCoefficient)
      }
    val roundTimer =
      RoundTimer(
        /* queue = */ bftEventQueue,
        /* roundExpiryTimeCalculator = */ roundTimeExpiryCalculator,
        /* bftExecutors = */ bftExecutors,
      )
    val blockTimer = BlockTimer(bftEventQueue, besuForksSchedule, bftExecutors, clock)
    val validatorMulticaster = P2PValidatorMulticaster(p2PNetwork)
    val finalState =
      QbftFinalStateAdapter(
        localAddress = localAddress,
        nodeKey = nodeKey,
        proposerSelector = qbftProposerSelector,
        validatorMulticaster = validatorMulticaster,
        roundTimer = roundTimer,
        blockTimer = blockTimer,
        blockCreatorFactory = qbftBlockCreatorFactory,
        clock = clock,
        beaconChain = beaconChain,
      )

    val minedBlockObservers = Subscribers.create<QbftMinedBlockObserver>()
    // Only broadcast to P2P when this node was the proposer — non-proposing validators
    // already received the block from P2P and re-broadcasting it would trigger
    // MessageAlreadySeenException from libp2p's deduplication.
    minedBlockObservers.subscribe { qbftBlock ->
      val sealedBlock = qbftBlock.toSealedBeaconBlock()
      if (sealedBlock.beaconBlock.beaconBlockHeader.proposer == localValidator) {
        blockMinedHandler.handleSealedBlock(sealedBlock)
      }
    }

    val blockImporter = QbftBlockImporterAdapter(sealedBeaconBlockImporter)

    val blockCodec = QbftBlockCodecAdapter
    val blockInterface = QbftBlockInterfaceAdapter(stateTransition)
    val beaconBlockValidatorFactory =
      BeaconBlockValidatorFactoryImpl(
        beaconChain = beaconChain,
        proposerSelector = proposerSelector,
        stateTransition = stateTransition,
        executionLayerManager = if (payloadValidationEnabled) executionLayerManager else null,
        allowEmptyBlocks = allowEmptyBlocks,
      )
    val protocolSchedule =
      QbftProtocolScheduleAdapter(
        blockImporter = blockImporter,
        beaconBlockValidatorFactory = beaconBlockValidatorFactory,
      )
    val messageValidatorFactory =
      MessageValidatorFactory(
        /* proposerSelector = */ qbftProposerSelector,
        /* protocolSchedule = */ protocolSchedule,
        /* validatorProvider = */ besuValidatorProvider,
        /* blockInterface = */ blockInterface,
      )
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
        /* validatorProvider = */ besuValidatorProvider,
        /* validatorModeTransitionLogger = */ transitionLogger,
      )
    val duplicateMessageTracker = MessageTracker(qbftOptions.duplicateMessageLimit)
    val chainHeaderNumber =
      beaconChain
        .getLatestBeaconState()
        .beaconBlockHeader
        .number
        .toLong()
    val futureMessageBuffer =
      FutureMessageBuffer<QbftMessage>(
        /* futureMessagesMaxDistance = */ qbftOptions.futureMessageMaxDistance,
        /* futureMessagesLimit = */ qbftOptions.futureMessagesLimit,
        /* chainHeight = */ chainHeaderNumber,
      )
    val gossiper = QbftGossiper(validatorMulticaster)
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

    val onRoundStartedCallback: (Long, Int) -> Unit = onRoundStartedCallback@{ blockNumber, roundJustStarted ->
      val latestBeaconState = beaconChain.getLatestBeaconState()
      // Guard: ignore stale events (block already advanced past this height)
      if (latestBeaconState.beaconBlockHeader.number.toLong() + 1L == blockNumber) {
        val nextRound = roundJustStarted + 1
        val nextRoundProposer =
          proposerSelector
            .getProposerForBlock(latestBeaconState, ConsensusRoundIdentifier(blockNumber, nextRound))
            .get() // ProposerSelectorImpl is synchronous (pure computation)
        if (localValidator.address.contentEquals(nextRoundProposer.address)) {
          val parentBlock =
            beaconChain.getSealedBeaconBlock(latestBeaconState.beaconBlockHeader.hash)?.beaconBlock
              ?: run {
                log.warn(
                  "Parent block not found for round-start build trigger (block={}), skipping",
                  blockNumber,
                )
                return@onRoundStartedCallback
              }
          val headHash =
            if (parentBlock.beaconBlockHeader.number == 0UL) {
              executionLayerManager.getLatestBlockHash().get()
            } else {
              parentBlock.beaconBlockBody.executionPayload.blockHash
            }
          val finState = finalizationStateProvider(parentBlock.beaconBlockBody)
          val nextBlockTimestamp =
            nextBlockTimestampProvider.nextTargetBlockUnixTimestamp(
              latestBeaconState.beaconBlockHeader.timestamp,
            )
          log.debug(
            "Round {} started for block {}, pre-building as round-{} proposer",
            roundJustStarted,
            blockNumber,
            nextRound,
          )
          executionLayerManager.setHeadAndStartBlockBuilding(
            headHash = headHash,
            safeHash = finState.safeBlockHash,
            finalizedHash = finState.finalizedBlockHash,
            nextBlockTimestamp = nextBlockTimestamp,
            feeRecipient = qbftOptions.feeRecipient,
            prevRandao =
              prevRandaoProvider.calculateNextPrevRandao(
                signee =
                  parentBlock.beaconBlockBody.executionPayload.blockNumber
                    .inc(),
                prevRandao = parentBlock.beaconBlockBody.executionPayload.prevRandao,
              ),
          )
        }
      }
    }
    val eventMultiplexer =
      QbftEventMultiplexer(qbftController).also {
        it.onBlockTimerFired = onBlockTimerFired
        it.onRoundStarted = onRoundStartedCallback
      }
    val eventProcessor = QbftEventProcessor(bftEventQueue, eventMultiplexer)
    val eventQueueExecutor =
      Executors.newSingleThreadExecutor(
        Thread
          .ofPlatform()
          .name("qbft-event-loop-${localAddress.toHexString().takeLast(8)}")
          .priority(Thread.MAX_PRIORITY)
          .daemon(true)
          .factory(),
      )

    val messageDecoder = MinimalQbftMessageDecoder(SecpCrypto)
    val qbftMessageProcessor =
      QbftMessageProcessor(
        blockChain = blockChain,
        validatorProvider = besuValidatorProvider,
        localAddress = localAddress,
        bftEventQueue = bftEventQueue,
        messageDecoder = messageDecoder,
      )

    // Subscribe to QBFT messages from P2P network and validate before adding to event queue
    p2PNetwork.subscribeToQbftMessages(qbftMessageProcessor)

    // Mirror Besu's BftMiningCoordinator.onBlockAdded: advance the QBFT state machine
    // for every new canonical head, whether from consensus or CL sync.
    val beaconChainObserverId = "qbft-new-chain-head-$localAddress"
    beaconChain.addSyncSubscriber(beaconChainObserverId) { sealedBlock ->
      bftEventQueue.add(QbftNewChainHead(QbftBlockHeaderAdapter(sealedBlock.beaconBlock.beaconBlockHeader)))
    }

    return QbftConsensusValidator(
      qbftController = qbftController,
      eventProcessor = eventProcessor,
      bftExecutors = bftExecutors,
      eventQueueExecutor = eventQueueExecutor,
      blockAdded = beaconChain,
      beaconChainObserverId = beaconChainObserverId,
    )
  }

  private fun createSealedBeaconBlockImporter(
    executionLayerManager: ExecutionLayerManager,
    beaconChain: BeaconChain,
    stateTransition: StateTransition,
    finalizationStateProvider: FinalizationProvider,
    prevRandaoProvider: PrevRandaoProvider<ULong>,
    feeRecipient: ByteArray,
    localValidator: Validator,
    proposerSelector: ProposerSelector,
  ): SealedBeaconBlockImporter<ValidationResult> {
    val shouldBuildNextBlock =
      { beaconState: BeaconState, _: ConsensusRoundIdentifier, nextBlockTimestamp: ULong ->
        // We shouldn't build next block if this fork ends.
        val nextForkTimestamp =
          forksSchedule.getNextForkByTimestamp(beaconState.beaconBlockHeader.timestamp)?.timestampSeconds
            ?: ULong.MAX_VALUE
        if (nextBlockTimestamp >= nextForkTimestamp) {
          false
        } else {
          // Build only if this node is the round-0 proposer for the next block.
          // Round-change proposers (round 1+) are covered by the onRoundStarted trigger in
          // QbftEventMultiplexer which fires at the beginning of each round.
          val nextBlockNumber = beaconState.beaconBlockHeader.number + 1u
          val round0Proposer =
            proposerSelector
              .getProposerForBlock(beaconState, ConsensusRoundIdentifier(nextBlockNumber.toLong(), 0))
              .get() // ProposerSelectorImpl is synchronous (pure computation)
          localValidator.address.contentEquals(round0Proposer.address)
        }
      }
    val beaconBlockImporter =
      BlockBuildingBeaconBlockImporter(
        executionLayerManager = executionLayerManager,
        finalizationStateProvider = finalizationStateProvider,
        nextBlockTimestampProvider = nextBlockTimestampProvider,
        prevRandaoProvider = prevRandaoProvider,
        shouldBuildNextBlock = shouldBuildNextBlock,
        feeRecipient = feeRecipient,
      )
    return TransactionalSealedBeaconBlockImporter(
      beaconChain = beaconChain,
      stateTransition = stateTransition,
      beaconBlockImporter = beaconBlockImporter,
    )
  }
}
