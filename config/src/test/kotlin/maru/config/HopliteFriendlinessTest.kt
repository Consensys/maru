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
import java.net.URI
import kotlin.io.path.Path
import kotlin.time.Duration.Companion.milliseconds
import maru.extensions.fromHexToByteArray
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HopliteFriendlinessTest {
  @Test
  fun appConfigFileIsParseable() {
    val config =
      Utils.parseTomlConfig<MaruConfigDtoToml>(
        """
        [execution-client]
        ethereum-json-rpc-endpoint = "http://localhost:8545"
        engine-api-json-rpc-endpoint = "http://localhost:8555"
        min-time-between-get-payload-attempts=800m

        [qbft-options]
        communication-margin=100m
        data-path="/some/path"

        [p2p-config]
        port = 3322

        [validator]
        validator-key = "0x1dd171cec7e2995408b5513004e8207fe88d6820aeff0d82463b3e41df251aae"
        """.trimIndent(),
      )
    assertThat(config)
      .isEqualTo(
        MaruConfigDtoToml(
          executionClient =
            ExecutionClientConfig(
              ethereumJsonRpcEndpoint = URI.create("http://localhost:8545").toURL(),
              engineApiJsonRpcEndpoint = URI.create("http://localhost:8555").toURL(),
              minTimeBetweenGetPayloadAttempts = 800.milliseconds,
            ),
          qbftOptions = QbftOptions(100.milliseconds, Path("/some/path")),
          p2pConfig = P2P(port = 3322u),
          validator =
            ValidatorDtoToml(
              validatorKey = Secret("0x1dd171cec7e2995408b5513004e8207fe88d6820aeff0d82463b3e41df251aae"),
            ),
        ),
      )
  }

  @Test
  fun appConfigFileIsConvertableToDomain() {
    val config =
      Utils.parseTomlConfig<MaruConfigDtoToml>(
        """
        [execution-client]
        ethereum-json-rpc-endpoint = "http://localhost:8545"
        engine-api-json-rpc-endpoint = "http://localhost:8555"
        min-time-between-get-payload-attempts=800m

        [qbft-options]
        communication-margin=100m
        data-path="/some/path"

        [p2p-config]
        port = 3322

        [validator]
        validator-key = "0x1dd171cec7e2995408b5513004e8207fe88d6820aeff0d82463b3e41df251aae"
        """.trimIndent(),
      )
    assertThat(config.domainFriendly())
      .isEqualTo(
        MaruConfig(
          executionClientConfig =
            ExecutionClientConfig(
              engineApiJsonRpcEndpoint = URI.create("http://localhost:8555").toURL(),
              ethereumJsonRpcEndpoint = URI.create("http://localhost:8545").toURL(),
              minTimeBetweenGetPayloadAttempts = 800.milliseconds,
            ),
          qbftOptions = QbftOptions(100.milliseconds, Path("/some/path")),
          p2pConfig = P2P(port = 3322u),
          validator =
            Validator(
              validatorKey = "0x1dd171cec7e2995408b5513004e8207fe88d6820aeff0d82463b3e41df251aae".fromHexToByteArray(),
            ),
        ),
      )
  }
}
