package maru.consensus.core


/**
 * BeaconBlock will be part of the QBFT Proposal payload
 */
interface BeaconBlock {
  val beaconBlockHeader: BeaconBlockHeader
  val beaconBlockBody: BeaconBlockBody
}
