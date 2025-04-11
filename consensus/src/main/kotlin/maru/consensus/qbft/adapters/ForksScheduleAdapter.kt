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
package maru.consensus.qbft.adapters

import java.math.BigInteger
import java.util.Optional
import maru.consensus.ForkSpec
import maru.consensus.qbft.DUPLICATE_MESSAGE_LIMIT
import maru.consensus.qbft.FUTURE_MESSAGES_LIMIT
import maru.consensus.qbft.FUTURE_MESSAGE_MAX_DISTANCE
import maru.consensus.qbft.MESSAGE_QUEUE_LIMIT
import maru.consensus.qbft.QbftConsensusConfig
import org.apache.tuweni.bytes.Bytes
import org.hyperledger.besu.config.BftConfigOptions
import org.hyperledger.besu.datatypes.Address
import org.hyperledger.besu.consensus.common.ForkSpec as BesuForkSpec
import org.hyperledger.besu.consensus.common.ForksSchedule as BesuForksSchedule

class ForksScheduleAdapter(
  currentSpec: ForkSpec,
) : BesuForksSchedule<BftConfigOptions>(maruForkSpecsToBesu(currentSpec)) {
  companion object {
    fun maruForkSpecsToBesu(currentSpec: ForkSpec): MutableCollection<BesuForkSpec<BftConfigOptions>> =
      mutableListOf(BesuForkSpec(0, createBftConfig(currentSpec)))

    private fun createBftConfig(spec: ForkSpec): BftConfigOptions {
      val bftConfig =
        object : BftConfigOptions {
          override fun getEpochLength(): Long = 0

          override fun getBlockPeriodSeconds(): Int = spec.blockTimeSeconds

          override fun getEmptyBlockPeriodSeconds(): Int = 0

          override fun getBlockPeriodMilliseconds(): Long = 0

          override fun getRequestTimeoutSeconds(): Int = 0

          override fun getGossipedHistoryLimit(): Int = 0

          override fun getMessageQueueLimit(): Int = MESSAGE_QUEUE_LIMIT

          override fun getDuplicateMessageLimit(): Int = DUPLICATE_MESSAGE_LIMIT

          override fun getFutureMessagesLimit(): Int = FUTURE_MESSAGES_LIMIT.toInt()

          override fun getFutureMessagesMaxDistance(): Int = FUTURE_MESSAGE_MAX_DISTANCE.toInt()

          override fun getMiningBeneficiary(): Optional<Address> =
            Optional.of(Address.wrap(Bytes.wrap((spec.configuration as QbftConsensusConfig).feeRecipient)))

          override fun getBlockRewardWei(): BigInteger = BigInteger.ZERO

          override fun asMap(): Map<String, Any> = emptyMap()
        }
      return bftConfig
    }
  }
}
