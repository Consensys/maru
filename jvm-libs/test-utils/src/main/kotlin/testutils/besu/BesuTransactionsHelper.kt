/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package testutils.besu

import java.time.Duration
import org.apache.logging.log4j.Logger
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.hyperledger.besu.datatypes.Hash
import org.hyperledger.besu.tests.acceptance.dsl.account.Account
import org.hyperledger.besu.tests.acceptance.dsl.account.Accounts
import org.hyperledger.besu.tests.acceptance.dsl.blockchain.Amount
import org.hyperledger.besu.tests.acceptance.dsl.node.BesuNode
import org.hyperledger.besu.tests.acceptance.dsl.transaction.account.AccountTransactions
import org.hyperledger.besu.tests.acceptance.dsl.transaction.account.TransferTransaction
import org.hyperledger.besu.tests.acceptance.dsl.transaction.eth.EthTransactions

class BesuTransactionsHelper {
  private val ethTransactions = EthTransactions()
  private val accounts = Accounts(ethTransactions)
  private val accountTransactions = AccountTransactions(accounts)
  private val whaleAccount =
    Account.fromPrivateKey(
      ethTransactions,
      "Whale",
      "0x3a4ff6d22d7502ef2452368165422861c01a0f72f851793b372b87888dc3c453",
    )

  fun createAccount(accountName: String): Account = accounts.createAccount(accountName)

  fun createTransfer(
    recipient: Account,
    amount: Amount,
  ): TransferTransaction = accountTransactions.createTransfer(whaleAccount, recipient, amount)

  fun createTransfers(numberOfTransactions: UInt): TransferTransaction {
    val recipient = accounts.createAccount("recipient")

    return accountTransactions.createTransfer(whaleAccount, recipient, numberOfTransactions.toInt())
  }

  fun BesuNode.sendTransaction(
    logger: Logger,
    recipient: Account,
    amount: Amount,
  ): Hash {
    val transfer = this@BesuTransactionsHelper.createTransfer(recipient, amount)
    val txHash = this.execute(transfer)
    assertThat(txHash).isNotNull()
    logger.info("Sending transaction {}", txHash)
    return txHash
  }

  fun BesuNode.sendTransactionAndAssertExecution(
    logger: Logger,
    recipient: Account,
    amount: Amount,
  ) {
    val txHash = sendTransaction(logger, recipient, amount)

    await
      .pollInterval(Duration.ofMillis(100))
      .timeout(Duration.ofSeconds(30))
      .ignoreExceptions()
      .untilAsserted {
        val receipt = this.execute(ethTransactions.getTransactionReceipt(txHash.toString())).get()
        assertThat(receipt)
          .withFailMessage("Transaction receipt for $txHash not found")
          .isNotNull
        assertThat(receipt.status)
          .withFailMessage("Transaction $txHash failed with status: ${receipt.status}")
          .isEqualTo("0x1")
      }

    logger.info("Transaction {} was mined", txHash)
  }
}
