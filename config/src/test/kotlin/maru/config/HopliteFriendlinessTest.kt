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
package maru.config

import com.sksamuel.hoplite.Secret
import fromHexToByteArray
import java.net.URI
import kotlin.time.Duration.Companion.milliseconds
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HopliteFriendlinessTest {
  private val emptyFollowersConfig =
    """
    [sot-node]
    endpoint = "http://localhost:8545"

    [dummy-consensus-options]
    communication-time-margin=100m

    [p2p-config]
    port = 3322

    [validator]
    key = "0xdead"
    min-time-between-get-payload-attempts=800m
    endpoint = "http://localhost:8555"
    """.trimIndent()
  private val rawConfig =
    """
    $emptyFollowersConfig

    [followers]
    follower1 = "http://localhost:1234"
    """.trimIndent()

  @Test
  fun appConfigFileIsParseable() {
    val config =
      Utils.parseTomlConfig<MaruConfigDtoToml>(rawConfig)
    assertThat(config)
      .isEqualTo(
        MaruConfigDtoToml(
          sotNode =
            ExecutionClientConfig(
              endpoint = URI.create("http://localhost:8545").toURL(),
            ),
          dummyConsensusOptions = DummyConsensusOptionsDtoToml(100.milliseconds),
          p2pConfig = P2P(port = 3322u),
          validator =
            ValidatorDtoToml(
              endpoint = URI.create("http://localhost:8555").toURL(),
              key = Secret("0xdead"),
              minTimeBetweenGetPayloadAttempts = 800.milliseconds,
            ),
          followers =
            mapOf(
              "follower1" to URI.create("http://localhost:1234").toURL(),
            ),
        ),
      )
  }

  @Test
  fun supportsEmptyFollowers() {
    val config =
      Utils.parseTomlConfig<MaruConfigDtoToml>(emptyFollowersConfig)
    assertThat(config)
      .isEqualTo(
        MaruConfigDtoToml(
          sotNode =
            ExecutionClientConfig(
              endpoint = URI.create("http://localhost:8545").toURL(),
            ),
          dummyConsensusOptions = DummyConsensusOptionsDtoToml(100.milliseconds),
          p2pConfig = P2P(port = 3322u),
          validator =
            ValidatorDtoToml(
              endpoint = URI.create("http://localhost:8555").toURL(),
              key = Secret("0xdead"),
              minTimeBetweenGetPayloadAttempts = 800.milliseconds,
            ),
          followers = null,
        ),
      )
  }

  @Test
  fun appConfigFileIsConvertableToDomain() {
    val config =
      Utils.parseTomlConfig<MaruConfigDtoToml>(rawConfig)
    assertThat(config.domainFriendly())
      .isEqualTo(
        MaruConfig(
          sotNode =
            ExecutionClientConfig(
              endpoint = URI.create("http://localhost:8545").toURL(),
            ),
          dummyConsensusOptions = DummyConsensusOptions(100.milliseconds),
          p2pConfig = P2P(port = 3322u),
          validator =
            Validator(
              client =
                ValidatorClientConfig(
                  engineApiClientConfig = EngineApiClientConfig(URI.create("http://localhost:8555").toURL()),
                  minTimeBetweenGetPayloadAttempts = 800.milliseconds,
                ),
              key = "0xdead".fromHexToByteArray(),
            ),
          followers =
            FollowersConfig(
              mapOf(
                "follower1" to ExecutionClientConfig(URI.create("http://localhost:1234").toURL()),
              ),
            ),
        ),
      )
  }

  @Test
  fun emptyFollowersAreConvertableToDomain() {
    val config =
      Utils.parseTomlConfig<MaruConfigDtoToml>(emptyFollowersConfig)
    assertThat(config.domainFriendly())
      .isEqualTo(
        MaruConfig(
          sotNode =
            ExecutionClientConfig(
              endpoint = URI.create("http://localhost:8545").toURL(),
            ),
          dummyConsensusOptions = DummyConsensusOptions(100.milliseconds),
          p2pConfig = P2P(port = 3322u),
          validator =
            Validator(
              client =
                ValidatorClientConfig(
                  engineApiClientConfig = EngineApiClientConfig(URI.create("http://localhost:8555").toURL()),
                  minTimeBetweenGetPayloadAttempts = 800.milliseconds,
                ),
              key = "0xdead".fromHexToByteArray(),
            ),
          followers =
            FollowersConfig(
              emptyMap(),
            ),
        ),
      )
  }
}
