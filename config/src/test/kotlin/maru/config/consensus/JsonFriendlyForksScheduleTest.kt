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
package maru.config.consensus

import maru.config.consensus.delegated.ElDelegatedConfig
import maru.config.consensus.qbft.QbftConsensusConfig
import maru.consensus.ForkSpec
import maru.consensus.ForksSchedule
import maru.core.Validator
import maru.extensions.fromHexToByteArray
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test

class JsonFriendlyForksScheduleTest {
  private val genesisConfig =
    """
    {
      "chainId": 1337,
      "config": {
        "2": {
          "type": "delegated",
          "blockTimeSeconds": 4
        },
        "4": {
          "type": "qbft",
          "validatorSet": ["0x1b9abeec3215d8ade8a33607f2cf0f4f60e5f0d0"],
          "blockTimeSeconds": 6,
          "feeRecipient": "0x0000000000000000000000000000000000000000",
          "elFork": "Prague"
        }
      }
    }
    """.trimIndent()

  @Test
  fun genesisFileIsConvertableToDomain() {
    val config =
      Utils
        .parseBeaconChainConfig(
          genesisConfig,
        ).domainFriendly()
    Assertions.assertThat(config).isEqualTo(
      ForksSchedule(
        1337u,
        setOf(
          ForkSpec(
            timestampSeconds = 2,
            blockTimeSeconds = 4,
            ElDelegatedConfig,
          ),
          ForkSpec(
            timestampSeconds = 4,
            blockTimeSeconds = 6,
            configuration =
              QbftConsensusConfig(
                feeRecipient = "0x0000000000000000000000000000000000000000".fromHexToByteArray(),
                validatorSet =
                  setOf(
                    Validator("0x1b9abeec3215d8ade8a33607f2cf0f4f60e5f0d0".fromHexToByteArray()),
                  ),
                elFork = QbftConsensusConfig.Companion.ElFork.Prague,
              ),
          ),
        ),
      ),
    )
  }

  @Test
  fun parserFailsIfSomeConfigurationIsMissing() {
    val invalidConfiguration =
      """
      {
        "config": {
          "4": {
            "type": "qbft",
            "feeRecipient": "0x0000000000000000000000000000000000000000",
            "elFork": "Prague"
          }
        }
      }
      """.trimIndent()
    Assertions
      .assertThatThrownBy {
        Utils.parseBeaconChainConfig(
          invalidConfiguration,
        )
      }.isInstanceOf(Exception::class.java)
  }
}
