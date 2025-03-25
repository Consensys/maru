package maru.consensus.qbft

import org.hyperledger.besu.consensus.common.bft.network.ValidatorMulticaster
import org.hyperledger.besu.datatypes.Address

class NoopValidatorMulticaster: ValidatorMulticaster {
  override fun send(p0: org.hyperledger.besu.ethereum.p2p.rlpx.wire.MessageData) {
    TODO("Not yet implemented")
  }

  override fun send(
    p0: org.hyperledger.besu.ethereum.p2p.rlpx.wire.MessageData,
    p1: Collection<Address?>?
  ) {
    TODO("Not yet implemented")
  }
}
