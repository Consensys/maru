package maru.app.config

import java.net.URL

data class ExecutionClientConfig(val endpoint: URL)

data class P2P(val port: UInt = 9000u)

data class Validator(val validatorKey: ByteArray) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as Validator

    if (!validatorKey.contentEquals(other.validatorKey)) return false

    return true
  }

  override fun hashCode(): Int {
    return validatorKey.contentHashCode()
  }
}

data class BeaconGenesisConfig(val blockTimeMillis: UInt) {
  init {
    require(blockTimeMillis >= 0u) { "blockTimeMillis must be greater than zero" }
  }
}

data class MaruConfig(
  val executionClientConfig: ExecutionClientConfig,
  val p2pConfig: P2P?,
  val validator: Validator?,
)
