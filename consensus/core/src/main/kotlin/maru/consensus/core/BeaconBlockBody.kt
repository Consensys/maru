package maru.consensus.core

interface BeaconBlockBody {
  val prevBlockSeals: List<Seal>
  val executionPayload: ExecutionPayload
}
