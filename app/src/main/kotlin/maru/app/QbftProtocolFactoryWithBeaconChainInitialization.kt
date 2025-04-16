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
package maru.app

import java.time.Duration
import maru.config.MaruConfig
import maru.consensus.ElFork
import maru.consensus.ForkSpec
import maru.consensus.MetadataProvider
import maru.consensus.NewBlockHandler
import maru.consensus.NextBlockTimestampProvider
import maru.consensus.ProposerSelector
import maru.consensus.ProposerSelectorImpl
import maru.consensus.ProtocolFactory
import maru.consensus.StaticValidatorProvider
import maru.consensus.blockImport.BlockBuildingBeaconBlockImporter
import maru.consensus.blockImport.FollowerBeaconBlockImporter
import maru.consensus.blockImport.SealedBeaconBlockImporter
import maru.consensus.blockImport.TransactionalSealedBeaconBlockImporter
import maru.consensus.qbft.QbftConsensusConfig
import maru.consensus.qbft.QbftProtocolFactory
import maru.consensus.state.FinalizationState
import maru.consensus.state.StateTransition
import maru.consensus.state.StateTransitionImpl
import maru.core.BeaconBlock
import maru.core.BeaconBlockBody
import maru.core.BeaconBlockHeader
import maru.core.BeaconState
import maru.core.HashUtil
import maru.core.Protocol
import maru.core.SealedBeaconBlock
import maru.core.Validator
import maru.crypto.Crypto
import maru.database.BeaconChain
import maru.database.kv.KvDatabaseFactory
import maru.executionlayer.client.ExecutionLayerEngineApiClient
import maru.executionlayer.client.PragueWeb3JJsonRpcExecutionLayerEngineApiClient
import maru.executionlayer.manager.ExecutionLayerManager
import maru.executionlayer.manager.JsonRpcExecutionLayerManager
import maru.mappers.Mappers.toDomain
import maru.serialization.rlp.KeccakHasher
import maru.serialization.rlp.RLPSerializers
import maru.serialization.rlp.stateRoot
import org.hyperledger.besu.consensus.common.bft.ConsensusRoundIdentifier
import org.hyperledger.besu.plugin.services.MetricsSystem
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameter
import tech.pegasys.teku.ethereum.executionclient.web3j.Web3JClient
import tech.pegasys.teku.ethereum.executionclient.web3j.Web3JExecutionEngineClient
import tech.pegasys.teku.ethereum.executionclient.web3j.Web3jClientBuilder
import tech.pegasys.teku.infrastructure.time.SystemTimeProvider

class QbftProtocolFactoryWithBeaconChainInitialization(
  private val maruConfig: MaruConfig,
  private val metricsSystem: MetricsSystem,
  private val metadataProvider: MetadataProvider,
  private val finalizationStateProvider: (BeaconBlockHeader) -> FinalizationState,
  private val executionLayerClient: Web3j,
  private val nextTargetBlockTimestampProvider: NextBlockTimestampProvider,
  private val newBlockHandler: NewBlockHandler,
) : ProtocolFactory {
  init {
    require(maruConfig.validator != null) { "The validator is required when QBFT protocol is instantiated!" }
  }

  private val hasher = HashUtil.headerHash(RLPSerializers.BeaconBlockHeaderSerializer, KeccakHasher)

  private fun initializeDb(updater: BeaconChain.Updater) {
    val genesisExecutionPayload =
      executionLayerClient
        .ethGetBlockByNumber(DefaultBlockParameter.valueOf("latest"), true)
        .send()
        .block
        .toDomain()

    val beaconBlockBody = BeaconBlockBody(prevCommitSeals = emptyList(), executionPayload = genesisExecutionPayload)

    val beaconBlockHeader =
      BeaconBlockHeader(
        number = 0u,
        round = 0u,
        timestamp = genesisExecutionPayload.timestamp,
        proposer = Validator(genesisExecutionPayload.feeRecipient),
        parentRoot = ByteArray(32),
        stateRoot = ByteArray(32),
        bodyRoot = ByteArray(32),
        headerHashFunction = hasher,
      )

    val initialValidators = setOf(Crypto.privateKeyToValidator(maruConfig.validator!!.privateKey))
    val tmpGenesisStateRoot =
      BeaconState(
        latestBeaconBlockHeader = beaconBlockHeader,
        validators = initialValidators,
      )
    val stateRootHash = HashUtil.stateRoot(tmpGenesisStateRoot)

    val genesisBlockHeader = beaconBlockHeader.copy(stateRoot = stateRootHash)
    val genesisBlock = BeaconBlock(genesisBlockHeader, beaconBlockBody)
    val genesisStateRoot = BeaconState(genesisBlockHeader, initialValidators)
    updater.putBeaconState(genesisStateRoot)
    updater.putSealedBeaconBlock(SealedBeaconBlock(genesisBlock, emptyList()))
    updater.commit()
  }

  private fun createSealedBeaconBlockImporter(
    executionLayerManager: ExecutionLayerManager,
    proposerSelector: ProposerSelector,
    localNodeIdentity: Validator,
    beaconChain: BeaconChain,
    stateTransition: StateTransition,
  ): SealedBeaconBlockImporter {
    val finalizationStateProvider = { beaconBlockBody: BeaconBlockBody ->
      val hash = beaconBlockBody.executionPayload.blockHash
      FinalizationState(hash, hash)
    }
    val shouldBuildNextBlock =
      { beaconState: BeaconState, roundIdentifier: ConsensusRoundIdentifier ->
        val nextProposerAddress =
          proposerSelector.getProposerForBlock(beaconState, roundIdentifier).get().address
        nextProposerAddress.contentEquals(localNodeIdentity.address)
      }
    val blockBuilderIdentity = Crypto.privateKeyToValidator(maruConfig.validator!!.privateKey)
    val followerBeaconBlockImporter =
      FollowerBeaconBlockImporter(executionLayerManager, finalizationStateProvider = finalizationStateProvider)
    val beaconBlockImporter =
      BlockBuildingBeaconBlockImporter(
        executionLayerManager = executionLayerManager,
        finalizationStateProvider = finalizationStateProvider,
        nextBlockTimestampProvider = nextTargetBlockTimestampProvider,
        shouldBuildNextBlock = shouldBuildNextBlock,
        blockBuilderIdentity = blockBuilderIdentity,
        followerBeaconBlockImporter = followerBeaconBlockImporter,
      )
    return TransactionalSealedBeaconBlockImporter(beaconChain, stateTransition, beaconBlockImporter)
  }

  override fun create(forkSpec: ForkSpec): Protocol {
    require(forkSpec.configuration is QbftConsensusConfig) {
      "Unexpected fork specification! ${
        forkSpec
          .configuration
      } instead of ${QbftConsensusConfig::class.simpleName}"
    }
    val qbftConsensusConfig = forkSpec.configuration as QbftConsensusConfig

    val beaconChain =
      KvDatabaseFactory
        .createRocksDbDatabase(
          databasePath = maruConfig.qbftOptions.dataPath,
          metricsSystem = metricsSystem,
          metricCategory = MaruMetricsCategory.STORAGE,
          stateInitializer = ::initializeDb,
        )

    val engineApiExecutionLayerClient =
      buildExecutionEngineClient(
        maruConfig.validator!!
          .client.engineApiClientConfig.endpoint
          .toString(),
        qbftConsensusConfig.elFork,
      )
    val executionLayerManager =
      JsonRpcExecutionLayerManager(
        executionLayerEngineApiClient = engineApiExecutionLayerClient,
      )

    val localNodeIdentity = Crypto.privateKeyToValidator(maruConfig.validator!!.privateKey)
    val validatorProvider = StaticValidatorProvider(setOf(localNodeIdentity))
    val stateTransition = StateTransitionImpl(validatorProvider)
    val proposerSelector = ProposerSelectorImpl
    val sealedBeaconBlockImporter =
      createSealedBeaconBlockImporter(
        executionLayerManager = executionLayerManager,
        beaconChain = beaconChain,
        proposerSelector = proposerSelector,
        localNodeIdentity = localNodeIdentity,
        stateTransition = stateTransition,
      )
    val qbftProtocolFactory =
      QbftProtocolFactory(
        beaconChain = beaconChain,
        maruConfig = maruConfig,
        metricsSystem = metricsSystem,
        metadataProvider = metadataProvider,
        finalizationStateProvider = finalizationStateProvider,
        nextBlockTimestampProvider = nextTargetBlockTimestampProvider,
        newBlockHandler = newBlockHandler,
        sealedBeaconBlockImporter = sealedBeaconBlockImporter,
        executionLayerManager = executionLayerManager,
        stateTransition = stateTransition,
        proposerSelector = proposerSelector,
        validatorProvider = validatorProvider,
      )
    return qbftProtocolFactory.create(forkSpec)
  }

  private fun buildExecutionEngineClient(
    endpoint: String,
    elFork: ElFork,
  ): ExecutionLayerEngineApiClient {
    val web3JEngineApiClient: Web3JClient =
      Web3jClientBuilder()
        .endpoint(endpoint)
        .timeout(Duration.ofMinutes(1))
        .timeProvider(SystemTimeProvider.SYSTEM_TIME_PROVIDER)
        .executionClientEventsPublisher { }
        .build()
    val web3jExecutionLayerClient = Web3JExecutionEngineClient(web3JEngineApiClient)
    return when (elFork) {
      ElFork.Prague -> PragueWeb3JJsonRpcExecutionLayerEngineApiClient(web3jExecutionLayerClient)
    }
  }
}
