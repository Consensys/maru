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

import java.math.BigInteger
import org.assertj.core.api.Assertions.assertThat
import org.hyperledger.besu.tests.acceptance.dsl.node.BesuNode
import org.web3j.protocol.core.DefaultBlockParameter
import org.web3j.protocol.core.methods.response.EthBlock

object Checks {
  fun BesuNode.getMinedBlocks(blocksMined: Int): List<EthBlock.Block> =
    (1 until blocksMined)
      .map {
        this
          .nodeRequests()
          .eth()
          .ethGetBlockByNumber(
            DefaultBlockParameter.valueOf(BigInteger.valueOf(it.toLong())),
            false,
          ).sendAsync()
      }.map { it.get().block }

  fun List<EthBlock.Block>.verifyBlockTime() {
    val blockTimeSeconds = 1L
    val timestampsSeconds = this.map { it.timestamp.toLong() }
    (2.until(this.size)).forEach {
      assertThat(timestampsSeconds[it - 1]).isLessThan(timestampsSeconds[it])
      val actualBlockTime = timestampsSeconds[it] - timestampsSeconds[it - 1]
      assertThat(actualBlockTime)
        .withFailMessage("Timestamps: $timestampsSeconds")
        .isGreaterThanOrEqualTo(blockTimeSeconds)
      assertThat(actualBlockTime)
        .withFailMessage("Timestamps: $timestampsSeconds")
        .isLessThanOrEqualTo(blockTimeSeconds)
    }
  }
}
