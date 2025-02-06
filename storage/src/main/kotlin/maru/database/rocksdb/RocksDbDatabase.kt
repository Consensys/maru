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

import java.util.Optional
import maru.core.BeaconBlock
import maru.core.BeaconState
import maru.database.Database
import maru.database.Updater
import maru.serialization.rlp.getRoot
import tech.pegasys.teku.storage.server.kvstore.KvStoreAccessor
import tech.pegasys.teku.storage.server.kvstore.KvStoreAccessor.KvStoreTransaction
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

  override fun getLatestBeaconState(): Optional<BeaconState> = rocksDbInstance.get(Schema.LatestBeaconState)

  override fun getBeaconState(beaconBlockRoot: ByteArray): Optional<BeaconState> =
    rocksDbInstance.get(Schema.BeaconStateByBlockRoot, beaconBlockRoot)

  override fun getBeaconBlock(beaconBlockRoot: ByteArray): Optional<BeaconBlock> =
    rocksDbInstance.get(Schema.BeaconBlockByBlockRoot, beaconBlockRoot)

  override fun newUpdater(): Updater = RocksDbUpdater(this.rocksDbInstance)

  override fun close() {
    rocksDbInstance.close()
  }

  class RocksDbUpdater(
    rocksDbInstance: KvStoreAccessor,
  ) : Updater {
    private val transaction: KvStoreTransaction = rocksDbInstance.startTransaction()

    override fun setBeaconState(beaconState: BeaconState): Updater {
      transaction.put(Schema.BeaconStateByBlockRoot, beaconState.latestBeaconBlockRoot, beaconState)
      transaction.put(Schema.LatestBeaconState, beaconState)
      return this
    }

    override fun setBeaconBlock(beaconBlock: BeaconBlock): Updater {
      transaction.put(Schema.BeaconBlockByBlockRoot, beaconBlock.getRoot(), beaconBlock)
      return this
    }

    override fun commit() {
      transaction.commit()
    }

    override fun rollback() {
      transaction.rollback()
    }

    override fun close() {
      transaction.close()
    }
  }
}
