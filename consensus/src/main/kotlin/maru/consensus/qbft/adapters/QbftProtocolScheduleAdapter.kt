package maru.consensus.qbft.adapters

import org.hyperledger.besu.consensus.qbft.core.types.QbftBlockHeader
import org.hyperledger.besu.consensus.qbft.core.types.QbftBlockImporter
import org.hyperledger.besu.consensus.qbft.core.types.QbftBlockValidator
import org.hyperledger.besu.consensus.qbft.core.types.QbftProtocolSchedule

class QbftProtocolScheduleAdapter: QbftProtocolSchedule {
  override fun getBlockImporter(blockHeader: QbftBlockHeader?): QbftBlockImporter? {
    TODO("Not yet implemented")
  }

  override fun getBlockValidator(blockHeader: QbftBlockHeader?): QbftBlockValidator? {
    TODO("Not yet implemented")
  }

}
