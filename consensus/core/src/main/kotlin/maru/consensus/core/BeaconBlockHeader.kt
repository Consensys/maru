package maru.consensus.core

data class BeaconBlockHeader (
  val proposer: Validator,
  val parentRoot: ByteArray,
  val stateRoot: ByteArray
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as BeaconBlockHeader

    if (proposer != other.proposer) return false
    if (!parentRoot.contentEquals(other.parentRoot)) return false
    if (!stateRoot.contentEquals(other.stateRoot)) return false

    return true
  }

  override fun hashCode(): Int {
    var result = proposer.hashCode()
    result = 31 * result + parentRoot.contentHashCode()
    result = 31 * result + stateRoot.contentHashCode()
    return result
  }
}
