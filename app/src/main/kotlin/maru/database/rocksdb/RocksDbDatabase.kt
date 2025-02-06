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
package maru.database.rocksdb

import encodeHex
import maru.core.BeaconBlock
import maru.core.BeaconState
import maru.database.Database
import tech.pegasys.teku.infrastructure.async.SafeFuture
import tech.pegasys.teku.storage.server.kvstore.KvStoreAccessor
import tech.pegasys.teku.storage.server.kvstore.schema.KvStoreColumn
import tech.pegasys.teku.storage.server.kvstore.schema.KvStoreVariable

class RocksDbDatabase(
  private val rocksDbInstance: KvStoreAccessor,
) : Database {
  companion object {
    object Schema {
      val BeaconStateByBlockRoot: KvStoreColumn<ByteArray, BeaconState> =
        KvStoreColumn.create(
          1,
          KvStoreSerializers.BytesSerializer,
          KvStoreSerializers.BeaconStateSerializer,
        )

      val BeaconBlockByBlockRoot: KvStoreColumn<ByteArray, BeaconBlock> =
        KvStoreColumn.create(
          2,
          KvStoreSerializers.BytesSerializer,
          KvStoreSerializers.BeaconBlockSerializer,
        )

      val LatestBeaconState: KvStoreVariable<BeaconState> =
        KvStoreVariable.create(
          1,
          KvStoreSerializers.BeaconStateSerializer,
        )
    }
  }

  override fun getLatestBeaconState(): SafeFuture<BeaconState> {
    val latestBeaconState = rocksDbInstance.get(Schema.LatestBeaconState)
    return if (latestBeaconState.isEmpty) {
      SafeFuture.failedFuture(RuntimeException("Could not find latest beacon state"))
    } else {
      SafeFuture.completedFuture(latestBeaconState.get())
    }
  }

  override fun getBeaconState(beaconBlockRoot: ByteArray): SafeFuture<BeaconState> {
    val beaconState = rocksDbInstance.get(Schema.BeaconStateByBlockRoot, beaconBlockRoot)
    return if (beaconState.isEmpty) {
      SafeFuture.failedFuture(
        RuntimeException(
          "Could not find beacon state for beaconBlockRoot: ${
            beaconBlockRoot.encodeHex()
          }",
        ),
      )
    } else {
      SafeFuture.completedFuture(beaconState.get())
    }
  }

  override fun storeState(beaconState: BeaconState): SafeFuture<Unit> {
    rocksDbInstance.startTransaction().use { txn ->
      txn.put(Schema.BeaconStateByBlockRoot, beaconState.latestBeaconBlockRoot, beaconState)
      txn.put(Schema.LatestBeaconState, beaconState)
      txn.commit()
    }
    return SafeFuture.completedFuture(Unit)
  }

  override fun getBeaconBlock(beaconBlockRoot: ByteArray): SafeFuture<BeaconBlock> {
    val beaconBlock = rocksDbInstance.get(Schema.BeaconBlockByBlockRoot, beaconBlockRoot)
    return if (beaconBlock.isEmpty) {
      SafeFuture.failedFuture(
        RuntimeException("Could not find beacon block for beaconBlockRoot: ${beaconBlockRoot.encodeHex()}"),
      )
    } else {
      SafeFuture.completedFuture(beaconBlock.get())
    }
  }

  override fun storeBeaconBlock(
    beaconBlock: BeaconBlock,
    beaconBlockRoot: ByteArray,
  ): SafeFuture<Unit> {
    val txn = rocksDbInstance.startTransaction()
    txn.put(Schema.BeaconBlockByBlockRoot, beaconBlockRoot, beaconBlock)
    txn.commit()
    return SafeFuture.completedFuture(Unit)
  }

  override fun close() {
    rocksDbInstance.close()
  }
}
