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
package maru.consensus

import maru.config.consensus.delegated.ElDelegatedConfig
import maru.config.consensus.qbft.QbftConsensusConfig
import maru.core.Protocol

interface ProtocolFactory {
  fun create(forkSpec: ForkSpec): Protocol
}

class OmniProtocolFactory(
  private val qbftConsensusFactory: ProtocolFactory,
  private val elDelegatedConsensusFactory: ProtocolFactory,
) : ProtocolFactory {
  override fun create(forkSpec: ForkSpec): Protocol =
    when (forkSpec.configuration) {
      is QbftConsensusConfig -> {
        qbftConsensusFactory.create(forkSpec)
      }

      is ElDelegatedConfig -> {
        elDelegatedConsensusFactory.create(forkSpec)
      }

      else -> {
        throw IllegalArgumentException("Fork $forkSpec is unknown!")
      }
    }
}
