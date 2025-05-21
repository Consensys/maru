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

import java.net.URI
import kotlin.io.path.Path
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import maru.core.Validator
import maru.extensions.fromHexToByteArray
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HopliteFriendlinessTest {
  private val emptyFollowersConfig =
    """
    [persistence]
    data-path="/some/path"
    private-key-path = "/private-key/path"

    [qbft-options]
    validator-set = ["0x1b9abeec3215d8ade8a33607f2cf0f4f60e5f0d0"]

    [qbft-options.validator-duties]
    private-key = "0x1dd171cec7e2995408b5513004e8207fe88d6820aeff0d82463b3e41df251aae"
    communication-margin=100m

    [p2p-config]
    port = 3322
    ip-address = "127.0.0.1"
    static-peers = []
    reconnect-delay = 500m

    [payload-validator]
    engine-api-endpoint = { endpoint = "http://localhost:8555", jwt-secret-path = "/secret/path" }
    eth-api-endpoint = { endpoint = "http://localhost:8545" }
    """.trimIndent()
  private val rawConfig =
    """
    $emptyFollowersConfig

    [follower-engine-apis]
    follower1 = { endpoint = "http://localhost:1234", jwt-secret-path = "/secret/path" }
    follower2 = { endpoint = "http://localhost:4321" }
    """.trimIndent()

  @Test
  fun appConfigFileIsParseable() {
    val config =
      Utils.parseTomlConfig<MaruConfigDtoToml>(rawConfig)
    assertThat(config)
      .isEqualTo(
        MaruConfigDtoToml(
          persistence = Persistence(dataPath = Path("/some/path"), privateKeyPath = Path("/private-key/path")),
          qbftOptions =
            QbftOptionsDtoTomlFriendly(
              ValidatorDutiesDtoTomlFriendly(
                100.milliseconds,
              ),
              validatorSet = setOf("0x1b9abeec3215d8ade8a33607f2cf0f4f60e5f0d0"),
            ),
          p2pConfig =
            P2P(
              ipAddress = "127.0.0.1",
              port = "3322",
              staticPeers = emptyList(),
              reconnectDelay = 500.milliseconds,
            ),
          payloadValidator =
            PayloadValidatorDtoToml(
              ethApiEndpoint =
                ApiEndpointDtoToml(
                  endpoint = URI.create("http://localhost:8545").toURL(),
                ),
              engineApiEndpoint =
                ApiEndpointDtoToml(
                  endpoint = URI.create("http://localhost:8555").toURL(),
                  jwtSecretPath = "/secret/path",
                ),
            ),
          followerEngineApis =
            mapOf(
              "follower1" to
                ApiEndpointDtoToml(
                  URI.create("http://localhost:1234").toURL(),
                  jwtSecretPath =
                    "/secret/path",
                ),
              "follower2" to ApiEndpointDtoToml(URI.create("http://localhost:4321").toURL()),
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
          persistence = Persistence(Path("/some/path"), privateKeyPath = Path("/private-key/path")),
          qbftOptions =
            QbftOptionsDtoTomlFriendly(
              validatorDuties =
                ValidatorDutiesDtoTomlFriendly(
                  communicationMargin = 100.milliseconds,
                ),
              validatorSet = setOf("0x1b9abeec3215d8ade8a33607f2cf0f4f60e5f0d0"),
            ),
          p2pConfig =
            P2P(
              ipAddress = "127.0.0.1",
              port = "3322",
              staticPeers = emptyList(),
              reconnectDelay = 500.milliseconds,
            ),
          payloadValidator =
            PayloadValidatorDtoToml(
              ethApiEndpoint =
                ApiEndpointDtoToml(
                  endpoint = URI.create("http://localhost:8545").toURL(),
                ),
              engineApiEndpoint =
                ApiEndpointDtoToml(
                  endpoint = URI.create("http://localhost:8555").toURL(),
                  jwtSecretPath = "/secret/path",
                ),
            ),
          followerEngineApis = null,
        ),
      )
  }

  @Test
  fun appConfigFileIsConvertableToDomain() {
    val config = Utils.parseTomlConfig<MaruConfigDtoToml>(rawConfig)
    assertThat(config.domainFriendly())
      .isEqualTo(
        MaruConfig(
          persistence = Persistence(Path("/some/path"), privateKeyPath = Path("/private-key/path")),
          p2pConfig =
            P2P(
              ipAddress = "127.0.0.1",
              port = "3322",
              staticPeers = emptyList(),
              reconnectDelay = 500.milliseconds,
            ),
          validatorElNode =
            ValidatorElNode(
              engineApiEndpoint =
                ApiEndpointConfig(
                  URI.create("http://localhost:8555").toURL(),
                  jwtSecretPath = "/secret/path",
                ),
              ethApiEndpoint =
                ApiEndpointConfig(
                  endpoint = URI.create("http://localhost:8545").toURL(),
                ),
            ),
          qbftOptions =
            QbftOptions(
              validatorDuties =
                ValidatorDuties(
                  communicationMargin = 100.milliseconds,
                ),
              validatorSet =
                setOf(
                  Validator(
                    "0x1b9abeec3215d8ade8a33607f2cf0f4f60e5f0d0"
                      .fromHexToByteArray(),
                  ),
                ),
            ),
          followers =
            FollowersConfig(
              mapOf(
                "follower1" to ApiEndpointConfig(URI.create("http://localhost:1234").toURL(), "/secret/path"),
                "follower2" to ApiEndpointConfig(URI.create("http://localhost:4321").toURL()),
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
          persistence = Persistence(Path("/some/path"), privateKeyPath = Path("/private-key/path")),
          qbftOptions =
            QbftOptions(
              validatorDuties =
                ValidatorDuties(
                  communicationMargin = 100.milliseconds,
                ),
              validatorSet =
                setOf(
                  Validator(
                    "0x1b9abeec3215d8ade8a33607f2cf0f4f60e5f0d0"
                      .fromHexToByteArray(),
                  ),
                ),
            ),
          p2pConfig =
            P2P(
              ipAddress = "127.0.0.1",
              port = "3322",
              staticPeers = emptyList(),
              reconnectDelay = 500.milliseconds,
            ),
          validatorElNode =
            ValidatorElNode(
              engineApiEndpoint =
                ApiEndpointConfig(
                  URI.create("http://localhost:8555").toURL(),
                  jwtSecretPath = "/secret/path",
                ),
              ethApiEndpoint =
                ApiEndpointConfig(
                  endpoint = URI.create("http://localhost:8545").toURL(),
                ),
            ),
          followers =
            FollowersConfig(
              emptyMap(),
            ),
        ),
      )
  }

  private val validatorDuties =
    """
    private-key = "0x1dd171cec7e2995408b5513004e8207fe88d6820aeff0d82463b3e41df251aae"
    communication-margin=100m
    data-path="/some/path"
    message-queue-limit = 1000
    round-expiry = 1000
    duplicateMessageLimit = 100
    future-message-max-distance = 10
    future-messages-limit = 1000
    """.trimIndent()

  @Test
  fun validatorDutiesAreParseable() {
    val config =
      Utils.parseTomlConfig<ValidatorDutiesDtoTomlFriendly>(validatorDuties)
    assertThat(config)
      .isEqualTo(
        ValidatorDutiesDtoTomlFriendly(
          communicationMargin = 100.milliseconds,
          messageQueueLimit = 1000,
          roundExpiry = 1.seconds,
          duplicateMessageLimit = 100,
          futureMessageMaxDistance = 10L,
          futureMessagesLimit = 1000L,
        ),
      )
  }
}
