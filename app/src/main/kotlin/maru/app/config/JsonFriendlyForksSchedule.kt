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
package maru.app.config

import kotlin.time.Duration.Companion.milliseconds
import maru.consensus.ConsensusConfiguration
import maru.consensus.ForkSpec
import maru.consensus.ForksSchedule
import maru.consensus.delegated.ElDelegatedConsensus
import maru.consensus.dummy.DummyConsensusConfig
import org.apache.tuweni.bytes.Bytes

data class JsonFriendlyForksSchedule(
  val config: Map<String, Map<String, String>>,
) {
  fun domainFriendly(): ForksSchedule {
    val forkSpecs: List<ForkSpec> =
      config.map { (k, v) ->
        val type = v["type"].toString()
        ForkSpec(
          k.toULong(),
          mapObjectToConfiguration(type, v),
        )
      }
    return ForksSchedule(forkSpecs)
  }

  private fun mapObjectToConfiguration(
    type: String,
    obj: Map<String, String>,
  ): ConsensusConfiguration =
    when (type) {
      "dummy" -> {
        1.milliseconds
        DummyConsensusConfig(obj["blockTimeMillis"]!!.toUInt(), Bytes.fromHexString(obj["feeRecipient"]!!).toArray())
      }
      "delegated" -> {
        ElDelegatedConsensus.Config(obj["pollPeriodMillis"]!!.toInt().milliseconds)
      }

      else -> throw IllegalArgumentException("Unsupported fork type $type!")
    }
}
