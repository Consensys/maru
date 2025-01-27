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

import com.sksamuel.hoplite.Masked
import org.apache.tuweni.bytes.Bytes

data class ValidatorToml(
  val validatorKey: Masked,
) {
  fun reified(): Validator = Validator(Bytes.fromHexString(validatorKey.value).toArray())
}

data class MaruConfigDtoToml(
  private val executionClient: ExecutionClientConfig,
  private val feesRecipient: String,
  private val p2pConfig: P2P?,
  private val validator: ValidatorToml?,
) {
  fun reified(): MaruConfig =
    MaruConfig(executionClient, Bytes.fromHexString(feesRecipient).toArray(), p2pConfig, validator?.reified())
}
