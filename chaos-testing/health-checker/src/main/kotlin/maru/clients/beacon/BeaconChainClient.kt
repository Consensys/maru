/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.clients.beacon

import net.consensys.linea.async.toSafeFuture
import org.http4k.client.DualSyncAsyncHttpHandler
import org.http4k.client.OkHttp
import org.http4k.core.Body
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Status.Companion.OK
import org.http4k.format.Jackson.auto
import tech.pegasys.teku.infrastructure.async.SafeFuture

data class Genesis(
  val genesis_time: String,
  val genesis_validators_root: String,
  val genesis_fork_version: String,
)

data class SyncingStatus(
  val head_slot: String,
  val sync_distance: String,
  val is_syncing: Boolean,
  val is_optimistic: Boolean,
)

data class Validator(
  val index: String,
  val balance: String,
  val status: String,
  val validator: ValidatorData,
)

data class ValidatorData(
  val pubkey: String,
  val withdrawal_credentials: String,
  val effective_balance: String,
  val slashed: Boolean,
)

// Root response wrapper
data class GetBlockV2Response(
  val version: String,
  val execution_optimistic: Boolean,
  val finalized: Boolean,
  val data: Phase0SignedBeaconBlock,
)

// Versioned block that can be one of multiple types
sealed class VersionedSignedBeaconBlock

data class Phase0SignedBeaconBlock(
  val message: Phase0MaruBlock,
  val signature: String,
) : VersionedSignedBeaconBlock()

// Phase0 Block
data class Phase0MaruBlock(
  val slot: String,
  val proposer_index: String,
  val parent_root: String,
  val state_root: String,
  // val body: Phase0BlockBody
)

// Interface extracted from previous concrete client methods
interface BeaconChainClient {
  // node operations
  fun getSyncingStatus(): SafeFuture<SyncingStatus>

  // beacon chain operations
  fun getGenesis(): SafeFuture<Genesis>

  fun getValidators(stateId: String = "head"): SafeFuture<List<Validator>>

  fun getBlock(blockId: String): SafeFuture<GetBlockV2Response>
}

class Http4kBeaconChainClient(
  private val baseUrl: String,
  private val client: DualSyncAsyncHttpHandler = OkHttp(),
) : BeaconChainClient {
  private data class DataEnvelop<T>(
    val data: T,
  )

  // Response lenses for parsing
  private val genesisLens = Body.auto<DataEnvelop<Genesis>>().toLens()
  private val syncingLens = Body.auto<DataEnvelop<SyncingStatus>>().toLens()
  private val validatorsLens = Body.auto<DataEnvelop<List<Validator>>>().toLens()
  private val getBlockLens = Body.auto<GetBlockV2Response>().toLens()

  private fun <T> supply(block: () -> T): SafeFuture<T> = SafeFuture.supplyAsync(block).toSafeFuture()

  override fun getSyncingStatus(): SafeFuture<SyncingStatus> =
    supply {
      val request = Request(Method.GET, "$baseUrl/eth/v1/node/syncing")
      val response = client(request)
      when (response.status) {
        OK -> syncingLens(response).data
        else -> throw Exception("Failed to get syncing status: ${response.status}")
      }
    }

  override fun getGenesis(): SafeFuture<Genesis> =
    supply {
      val request = Request(Method.GET, "$baseUrl/eth/v1/beacon/genesis")
      val response = client(request)
      when (response.status) {
        OK -> genesisLens(response).data
        else -> throw Exception("Failed to get genesis: ${response.status}")
      }
    }

  override fun getValidators(stateId: String): SafeFuture<List<Validator>> =
    supply {
      val request = Request(Method.GET, "$baseUrl/eth/v1/beacon/states/$stateId/validators")
      val response = client(request)
      when (response.status) {
        OK -> validatorsLens(response).data
        else -> throw Exception("Failed to get validators: ${response.status}")
      }
    }

  override fun getBlock(blockId: String): SafeFuture<GetBlockV2Response> =
    supply {
      val request = Request(Method.GET, "$baseUrl/eth/v2/beacon/blocks/$blockId")
      val response = client(request)
      when (response.status) {
        OK -> getBlockLens(response)
        else -> throw Exception("Failed to get block: ${response.status}")
      }
    }
}

// Usage example
fun main() {
  val beaconClient: BeaconChainClient = Http4kBeaconChainClient("http://localhost:18080")
  // Example async call (commented):
  beaconClient.getSyncingStatus().get().also { println(it) }
  beaconClient.getBlock("head").get().also { println(it) }
}
