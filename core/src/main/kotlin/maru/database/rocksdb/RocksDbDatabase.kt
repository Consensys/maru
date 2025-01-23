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

import maru.core.BeaconBlock
import maru.core.BeaconState
import maru.database.Database
import org.apache.tuweni.bytes.Bytes
import tech.pegasys.teku.infrastructure.async.SafeFuture
import tech.pegasys.teku.storage.server.kvstore.schema.KvStoreColumn
import tech.pegasys.teku.storage.server.kvstore.schema.KvStoreVariable
import tech.pegasys.teku.storage.server.rocksdb.RocksDbInstance

class RocksDbDatabase(private val rocksDbInstance: RocksDbInstance) : Database {
  companion object {
    object SCHEMA {
      val BEACON_STATE_BY_BLOCK_ROOT: KvStoreColumn<ByteArray, BeaconState> =
        KvStoreColumn.create(
          1,
          KvStoreSerializers.BYTES_SERIALIZER,
          KvStoreSerializers.BEACON_STATE_SERIALIZER,
        )

      val BEACON_BLOCK_BY_BLOCK_ROOT: KvStoreColumn<ByteArray, BeaconBlock> =
        KvStoreColumn.create(
          2,
          KvStoreSerializers.BYTES_SERIALIZER,
          KvStoreSerializers.BEACON_BLOCK_SERIALIZER,
        )

      val LATEST_BEACON_STATE: KvStoreVariable<BeaconState> =
        KvStoreVariable.create(
          1,
          KvStoreSerializers.BEACON_STATE_SERIALIZER,
        )
    }
  }

  override fun getLatestBeaconState(): SafeFuture<BeaconState> {
    val latestBeaconState = rocksDbInstance.get(SCHEMA.LATEST_BEACON_STATE)
    return if (latestBeaconState.isEmpty) {
      SafeFuture.failedFuture(RuntimeException("Could not find latest beacon state"))
    } else {
      SafeFuture.completedFuture(latestBeaconState.get())
    }
  }

  override fun getBeaconState(beaconBlockRoot: ByteArray): SafeFuture<BeaconState> {
    val beaconState = rocksDbInstance.get(SCHEMA.BEACON_STATE_BY_BLOCK_ROOT, beaconBlockRoot)
    return if (beaconState.isEmpty) {
      SafeFuture.failedFuture(
        RuntimeException(
          "Could not find beacon state for beaconBlockRoot: ${Bytes.wrap(beaconBlockRoot).toHexString()}",
        ),
      )
    } else {
      SafeFuture.completedFuture(beaconState.get())
    }
  }

  override fun storeState(beaconState: BeaconState): SafeFuture<Unit> {
    rocksDbInstance.startTransaction().use { txn ->
      txn.put(SCHEMA.BEACON_STATE_BY_BLOCK_ROOT, beaconState.latestBeaconBlockRoot, beaconState)
      txn.put(SCHEMA.LATEST_BEACON_STATE, beaconState)
      txn.commit()
    }
    return SafeFuture.completedFuture(Unit)
  }

  @OptIn(ExperimentalStdlibApi::class)
  override fun getBeaconBlock(beaconBlockRoot: ByteArray): SafeFuture<BeaconBlock> {
    val beaconBlock = rocksDbInstance.get(SCHEMA.BEACON_BLOCK_BY_BLOCK_ROOT, beaconBlockRoot)
    return if (beaconBlock.isEmpty) {
      SafeFuture.failedFuture(
        RuntimeException(
          "Could not find beacon block for beaconBlockRoot: ${Bytes.wrap(beaconBlockRoot).toHexString()}",
        ),
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
    txn.put(SCHEMA.BEACON_BLOCK_BY_BLOCK_ROOT, beaconBlockRoot, beaconBlock)
    txn.commit()
    return SafeFuture.completedFuture(Unit)
  }

  override fun close() {
    rocksDbInstance.close()
  }
}
