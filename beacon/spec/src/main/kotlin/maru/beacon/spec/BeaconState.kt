package maru.beacon.spec

import org.apache.tuweni.units.bigints.UInt64
import org.hyperledger.besu.consensus.common.bft.ConsensusRoundIdentifier
import org.hyperledger.besu.config.Fork

interface BeaconState {
  val genesisTime: UInt64
  val fork: Fork
  val consensusRoundIdentifier: ConsensusRoundIdentifier
  val latestBeaconBlockHeader: BeaconBlockHeader
  val validators: Collection<Validator>
}
