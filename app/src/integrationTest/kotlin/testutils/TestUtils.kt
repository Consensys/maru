/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package testutils

import java.net.ServerSocket
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.apache.logging.log4j.LogManager
import org.hyperledger.besu.tests.acceptance.dsl.blockchain.Amount
import org.hyperledger.besu.tests.acceptance.dsl.node.BesuNode
import org.junit.jupiter.api.fail
import testutils.besu.BesuTransactionsHelper

object TestUtils {
  private val log = LogManager.getLogger(this.javaClass)

  fun findFreePort(): UInt =
    runCatching {
      ServerSocket(0).use { socket ->
        socket.reuseAddress = true
        socket.localPort.toUInt()
      }
    }.getOrElse {
      throw IllegalStateException("Could not find a free port", it)
    }

  fun startTransactionSendingJob(besuNode: BesuNode): Job {
    val handler =
      CoroutineExceptionHandler { _, exception ->
        fail("Transaction sending job failed with exception: $exception")
      }

    val job =
      CoroutineScope(Dispatchers.Default).launch(handler) {
        while (true) {
          BesuTransactionsHelper().run {
            besuNode.sendTransactionAndAssertExecution(
              logger = log,
              recipient = createAccount("another account"),
              amount = Amount.ether(1),
            )
          }
        }
      }

    return job
  }
}
