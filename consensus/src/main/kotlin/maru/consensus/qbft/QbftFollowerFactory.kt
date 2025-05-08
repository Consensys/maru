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
package maru.consensus.qbft

import maru.consensus.ForkSpec
import maru.consensus.NewBlockHandler
import maru.consensus.ProtocolFactory
import maru.consensus.StaticValidatorProvider
import maru.consensus.blockimport.TransactionalSealedBeaconBlockImporter
import maru.consensus.blockimport.ValidatingSealedBeaconBlockImporter
import maru.consensus.state.StateTransitionImpl
import maru.consensus.validation.BeaconBlockValidatorFactory
import maru.consensus.validation.SCEP256SealVerifier
import maru.core.Protocol
import maru.core.Validator
import maru.database.BeaconChain
import maru.executionlayer.manager.ExecutionLayerManager
import maru.p2p.P2PNetwork
import org.hyperledger.besu.crypto.SECP256R1

class QbftFollowerFactory(
  val p2PNetwork: P2PNetwork,
  val beaconChain: BeaconChain,
  val newBlockHandler: NewBlockHandler,
  val executionLayerManager: ExecutionLayerManager,
) : ProtocolFactory {
  override fun create(forkSpec: ForkSpec): Protocol {
    val validator = Validator((forkSpec.configuration as QbftConsensusConfig).feeRecipient)
    val validatorProvider = StaticValidatorProvider(validators = setOf(validator))
    val stateTransition = StateTransitionImpl(validatorProvider)
    val transactionalSealedBeaconBlockImporter =
      TransactionalSealedBeaconBlockImporter(beaconChain, stateTransition) { _, beaconBlock ->
        newBlockHandler.handleNewBlock(beaconBlock)
      }
    val sealVerifier = SCEP256SealVerifier(SECP256R1())

    val beaconBlockValidatorFactory =
      BeaconBlockValidatorFactory(
        beaconChain = beaconChain,
        proposerSelector = ProposerSelectorImpl,
        stateTransition = stateTransition,
        executionLayerManager = executionLayerManager,
      )
    val blockImporter =
      ValidatingSealedBeaconBlockImporter(
        sealVerifier = sealVerifier,
        beaconBlockImporter = transactionalSealedBeaconBlockImporter,
        validatorProvider = validatorProvider,
        beaconBlockValidatorFactory = beaconBlockValidatorFactory,
      )
    return QbftConsensusFollower(p2PNetwork, blockImporter)
  }
}
