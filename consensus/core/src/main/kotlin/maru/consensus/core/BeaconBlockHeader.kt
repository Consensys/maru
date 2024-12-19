package maru.consensus.core

interface BeaconBlockHeader {
  val proposer: Validator
  val parentRoot: ByteArray
  val stateRoot: ByteArray
}
