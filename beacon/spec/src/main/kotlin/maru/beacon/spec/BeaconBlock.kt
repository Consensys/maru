package maru.beacon.spec

import org.apache.tuweni.bytes.Bytes32
import org.hyperledger.besu.consensus.common.bft.ConsensusRoundIdentifier

interface BeaconBlock {
  val consensusRoundIdentifier: ConsensusRoundIdentifier
  val proposer: Validator
  val parentRoot: Bytes32
  val stateRoot: Bytes32
  val beaconBlockBody: BeaconBlockBody
}
