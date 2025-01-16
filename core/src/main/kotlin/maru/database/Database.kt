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
package maru.database

import maru.consensus.core.BeaconBlock
import maru.consensus.core.BeaconState
import tech.pegasys.teku.infrastructure.async.SafeFuture

interface Database {
  fun getLatestBeaconState(): SafeFuture<BeaconState>

  fun getBeaconState(beaconBlockRoot: ByteArray): SafeFuture<BeaconState>

  fun storeState(beaconState: BeaconState): SafeFuture<Void>

  fun getBeaconBlock(beaconBlockRoot: ByteArray): SafeFuture<BeaconBlock>

  fun storeBeaconBlock(beaconBlock: BeaconBlock): SafeFuture<Void>
}
