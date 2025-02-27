/*
   Copyright 2025 Consensys Software Inc.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */
package maru.consensus.qbft.adaptors

import maru.core.BeaconBlock
import maru.core.BeaconBlockHeader
import org.hyperledger.besu.consensus.qbft.core.types.QbftBlock
import org.hyperledger.besu.consensus.qbft.core.types.QbftBlockHeader

/**
* Utility class to convert [QbftBlock] to [BeaconBlock] and [QbftBlockHeader] to [BeaconBlockHeader].
 */
object BlockUtil {
  /**
   * Convert a QBFT block to a BeaconBlock
   *
   * @param qbftBlock the QBFT block to convert
   */
  fun toBeaconBlock(qbftBlock: QbftBlock): BeaconBlock {
    if (qbftBlock is QbftBlockAdaptor) {
      return qbftBlock.beaconBlock
    } else {
      throw IllegalArgumentException("Unsupported block type")
    }
  }

  /**
   * Convert a QBFT block header to a BeaconBlockHeader
   *
   * @param qbftBlockHeader the QBFT block header to convert
   */
  fun toBeaconBlockHeader(qbftBlockHeader: QbftBlockHeader): BeaconBlockHeader {
    if (qbftBlockHeader is QbftBlockHeaderAdaptor) {
      return qbftBlockHeader.beaconBlockHeader
    } else {
      throw IllegalArgumentException("Unsupported block header type")
    }
  }
}
