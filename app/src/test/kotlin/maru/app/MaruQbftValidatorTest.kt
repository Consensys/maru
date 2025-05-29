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

import com.github.michaelbull.result.Ok
import maru.app.Checks.getMinedBlocks
import maru.app.Checks.verifyBlockTime
import maru.app.Checks.verifyBlockTimeWithAGapOn
import maru.consensus.StaticValidatorProvider
import maru.consensus.qbft.toAddress
import maru.consensus.validation.QuorumOfSealsVerifier
import maru.consensus.validation.SCEP256SealVerifier
import maru.core.Validator
import maru.crypto.Crypto
import maru.extensions.fromHexToByteArray
import maru.p2p.NoOpP2PNetwork
import maru.testutils.MaruFactory
import maru.testutils.MaruFactory.VALIDATOR_ADDRESS
import maru.testutils.NetworkParticipantStack
import maru.testutils.SpyingP2PNetwork
import maru.testutils.besu.BesuTransactionsHelper
import org.apache.logging.log4j.LogManager
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.hyperledger.besu.consensus.qbft.core.messagewrappers.Commit
import org.hyperledger.besu.consensus.qbft.core.messagewrappers.Prepare
import org.hyperledger.besu.consensus.qbft.core.messagewrappers.Proposal
import org.hyperledger.besu.consensus.qbft.core.messagewrappers.RoundChange
import org.hyperledger.besu.tests.acceptance.dsl.blockchain.Amount
import org.hyperledger.besu.tests.acceptance.dsl.condition.net.NetConditions
import org.hyperledger.besu.tests.acceptance.dsl.node.ThreadBesuNodeRunner
import org.hyperledger.besu.tests.acceptance.dsl.node.cluster.Cluster
import org.hyperledger.besu.tests.acceptance.dsl.node.cluster.ClusterConfigurationBuilder
import org.hyperledger.besu.tests.acceptance.dsl.transaction.net.NetTransactions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated

@Isolated
class MaruQbftValidatorTest {
  private lateinit var cluster: Cluster
  private lateinit var networkParticipantStack: NetworkParticipantStack
  private lateinit var transactionsHelper: BesuTransactionsHelper
  private val log = LogManager.getLogger(this.javaClass)
  private lateinit var spyingP2pNetwork: SpyingP2PNetwork

  @BeforeEach
  fun setUp() {
    transactionsHelper = BesuTransactionsHelper()
    cluster =
      Cluster(
        ClusterConfigurationBuilder().build(),
        NetConditions(NetTransactions()),
        ThreadBesuNodeRunner(),
      )

    spyingP2pNetwork = SpyingP2PNetwork(NoOpP2PNetwork)
    networkParticipantStack = NetworkParticipantStack(p2pNetwork = spyingP2pNetwork, cluster = cluster)
    networkParticipantStack.maruApp.start()
  }

  @AfterEach
  fun tearDown() {
    cluster.close()
    networkParticipantStack.maruApp.stop()
  }

  @Test
  fun `Maru is producing blocks with expected block time and emits messages`() {
    val blocksToProduce = 10
    repeat(blocksToProduce) {
      transactionsHelper.run {
        networkParticipantStack.besuNode.sendTransactionAndAssertExecution(
          logger = log,
          recipient = createAccount("another account"),
          amount = Amount.ether(100),
        )
      }
    }

    val blocks = networkParticipantStack.besuNode.getMinedBlocks(blocksToProduce)
    blocks.verifyBlockTime()

    // Need to wait because otherwise not all of the messages might be emitted at the time of a block being mined
    await.untilAsserted {
      validateRoundChange(maxBlockNumber = blocksToProduce.toLong())
    }

    for (blockNumber in 1L..blocksToProduce) {
      assertThat(
        anyPrepareWithBlockNumber(blockNumber),
      ).withFailMessage { "Didn't find any prepare messages for blockNumber=$blockNumber" }
        .isTrue
      assertThat(
        anyCommitWithBlockNumber(blockNumber),
      ).withFailMessage { "Didn't find any commit messages for blockNumber=$blockNumber" }
        .isTrue
      assertThat(
        anyProposalWithBlockNumber(blockNumber),
      ).withFailMessage { "Didn't find any proposal messages for blockNumber=$blockNumber" }
        .isTrue
    }
    allMessagesAreSignedByTheExpectedSigner()
    allBlocksAreSignedByTheExpectedSigner()
  }

  private fun anyPrepareWithBlockNumber(blockNumber: Long): Boolean =
    spyingP2pNetwork.emittedQbftMessages.any {
      it is Prepare && it.roundIdentifier.sequenceNumber == blockNumber
    }

  private fun anyProposalWithBlockNumber(blockNumber: Long): Boolean =
    spyingP2pNetwork.emittedQbftMessages.any {
      it is Proposal && it.roundIdentifier.sequenceNumber == blockNumber
    }

  private fun anyCommitWithBlockNumber(blockNumber: Long): Boolean =
    spyingP2pNetwork.emittedQbftMessages.any {
      it is Commit && it.roundIdentifier.sequenceNumber == blockNumber
    }

  private fun allBlocksAreSignedByTheExpectedSigner() {
    val validatorProvider = StaticValidatorProvider(setOf(Validator(VALIDATOR_ADDRESS.fromHexToByteArray())))
    val sealVerifier = SCEP256SealVerifier()
    val sealsVerifier = QuorumOfSealsVerifier(validatorProvider = validatorProvider, sealVerifier)
    spyingP2pNetwork.emittedBlockMessages.forEach { sealedBeaconBlock ->
      val verificationResult =
        sealsVerifier
          .verifySeals(
            sealedBeaconBlock.commitSeals,
            sealedBeaconBlock.beaconBlock.beaconBlockHeader,
          ).get()
      assertThat(verificationResult).isEqualTo(Ok(Unit))
    }
  }

  // All blocks except the first must be produced within 0th round. Absence of transactions will trigger RoundChange
  // events post test
  private fun validateRoundChange(maxBlockNumber: Long) {
    val roundChangeMessages =
      spyingP2pNetwork.emittedQbftMessages.filter {
        it is RoundChange
      }
    assertThat(roundChangeMessages).isNotEmpty()
    roundChangeMessages.forEach { roundChange ->
      assertThat(
        roundChange.roundIdentifier.sequenceNumber == 1L ||
          roundChange.roundIdentifier.sequenceNumber > maxBlockNumber,
      ).withFailMessage { "Unexpected RoundChange! $roundChange" }
        .isTrue
    }
  }

  private fun allMessagesAreSignedByTheExpectedSigner() {
    val validatorAddress =
      Crypto
        .privateKeyToValidator(MaruFactory.VALIDATOR_PRIVATE_KEY.fromHexToByteArray())
        .toAddress()
    spyingP2pNetwork.emittedQbftMessages.forEach {
      assertThat(it.author)
        .withFailMessage { "Unexpected signer address for message=$it author=${it.author}" }
        .isEqualTo(validatorAddress)
    }
  }

  @Test
  fun `Maru works if Besu stops mid flight`() {
    val blocksToProduce = 5
    repeat(blocksToProduce) {
      transactionsHelper.run {
        networkParticipantStack.besuNode.sendTransactionAndAssertExecution(
          logger = log,
          recipient = createAccount("another account"),
          amount = Amount.ether(100),
        )
      }
    }
    cluster.stop()
    Thread.sleep(3000)
    cluster.start(networkParticipantStack.besuNode)

    repeat(blocksToProduce) {
      transactionsHelper.run {
        networkParticipantStack.besuNode.sendTransactionAndAssertExecution(
          logger = log,
          recipient = createAccount("another account"),
          amount = Amount.ether(100),
        )
      }
    }

    val blocks = networkParticipantStack.besuNode.getMinedBlocks(blocksToProduce * 2)
    blocks.verifyBlockTimeWithAGapOn(blocksToProduce)
  }

  @Test
  fun `Maru works after restart`() {
    val blocksToProduce = 5
    repeat(blocksToProduce) {
      transactionsHelper.run {
        networkParticipantStack.besuNode.sendTransactionAndAssertExecution(
          logger = log,
          recipient = createAccount("another account"),
          amount = Amount.ether(100),
        )
      }
    }
    networkParticipantStack.maruApp.stop()
    Thread.sleep(3000)
    networkParticipantStack.maruApp.start()
    repeat(blocksToProduce) {
      transactionsHelper.run {
        networkParticipantStack.besuNode.sendTransactionAndAssertExecution(
          logger = log,
          recipient = createAccount("another account"),
          amount = Amount.ether(100),
        )
      }
    }

    val blocks = networkParticipantStack.besuNode.getMinedBlocks(blocksToProduce * 2)
    blocks.verifyBlockTimeWithAGapOn(blocksToProduce)
  }

  @Test
  fun `Maru works after recreation`() {
    val blocksToProduce = 5
    repeat(blocksToProduce) {
      transactionsHelper.run {
        networkParticipantStack.besuNode.sendTransactionAndAssertExecution(
          logger = log,
          recipient = createAccount("another account"),
          amount = Amount.ether(100),
        )
      }
    }
    networkParticipantStack.maruApp.stop()
    networkParticipantStack.maruApp.close()

    Thread.sleep(3000)
    networkParticipantStack.maruApp =
      MaruFactory.buildTestMaruValidatorWithoutP2p(
        ethereumJsonRpcUrl = networkParticipantStack.besuNode.jsonRpcBaseUrl().get(),
        engineApiRpc = networkParticipantStack.besuNode.engineRpcUrl().get(),
        dataDir = networkParticipantStack.tmpDir,
      )
    // The difference from the previous test is that BeaconChain is instantiated with the Maru instance and it's not
    // affected by start and stop
    networkParticipantStack.maruApp.start()
    repeat(blocksToProduce) {
      transactionsHelper.run {
        networkParticipantStack.besuNode.sendTransactionAndAssertExecution(
          logger = log,
          recipient = createAccount("another account"),
          amount = Amount.ether(100),
        )
      }
    }

    val blocks = networkParticipantStack.besuNode.getMinedBlocks(blocksToProduce * 2)

    blocks.verifyBlockTimeWithAGapOn(blocksToProduce)
  }
}
