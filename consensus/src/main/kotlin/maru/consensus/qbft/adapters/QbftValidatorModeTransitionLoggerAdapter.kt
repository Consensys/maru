package maru.consensus.qbft.adapters

import org.hyperledger.besu.consensus.qbft.core.types.QbftBlockHeader
import org.hyperledger.besu.consensus.qbft.core.types.QbftValidatorModeTransitionLogger

class QbftValidatorModeTransitionLoggerAdapter: QbftValidatorModeTransitionLogger {
  override fun logTransitionChange(parentHeader: QbftBlockHeader) {
  }

}
