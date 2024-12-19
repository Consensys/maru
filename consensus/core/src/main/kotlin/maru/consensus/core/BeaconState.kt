package maru.consensus.core

interface BeaconState {
  val latestBeaconBlockHeader: BeaconBlockHeader
  val latestBeaconBlockRoot: ByteArray
  val validators: Collection<Validator>
}
