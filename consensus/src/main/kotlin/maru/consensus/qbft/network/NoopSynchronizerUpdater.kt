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
package maru.consensus.qbft.network

import org.hyperledger.besu.consensus.common.bft.SynchronizerUpdater
import org.hyperledger.besu.ethereum.p2p.rlpx.connections.PeerConnection

class NoopSynchronizerUpdater : SynchronizerUpdater {
  override fun updatePeerChainState(
    knownBlockNumber: Long,
    peerConnection: PeerConnection?,
  ) {
  }
}
