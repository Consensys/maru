package maru.app.config

import com.sksamuel.hoplite.Masked

data class ValidatorToml(val validatorKey: Masked) {
  fun reified(): Validator {
    // TODO: This is incorrect, fix with an imported utility
    return Validator(validatorKey.value.encodeToByteArray())
  }
}

data class MaruConfigDtoToml(
  private val executionClient: ExecutionClientConfig,
  private val p2pConfig: P2P?,
  private val validator: ValidatorToml?,
) {
  fun reified(): MaruConfig {
    return MaruConfig(executionClient, p2pConfig, validator?.reified())
  }
}
