package maru.consensus.qbft.adapters

import org.apache.tuweni.bytes.Bytes
import org.hyperledger.besu.consensus.common.bft.ConsensusRoundIdentifier
import org.hyperledger.besu.consensus.common.bft.blockcreation.ProposerSelector
import org.hyperledger.besu.datatypes.Address

class ProposerSelectorAdapter(private val proposerSelector: maru.consensus.ProposerSelector): ProposerSelector {


  override fun selectProposerForRound(roundIdentifier: ConsensusRoundIdentifier?): Address? {
    val validator = proposerSelector.selectProposerForRound(roundIdentifier).get()
    return Address.wrap(Bytes.wrap(validator.address))
  }
}
