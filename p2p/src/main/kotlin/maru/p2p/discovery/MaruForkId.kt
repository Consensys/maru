/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.p2p.discovery

import java.util.Optional
import org.apache.tuweni.bytes.Bytes
import org.apache.tuweni.bytes.Bytes32
import org.apache.tuweni.units.bigints.UInt64
import org.ethereum.beacon.discovery.schema.NodeRecord

private val MaruForkId.Companion.MARU_EMPTY_FORK_ID: MaruForkId
  get() = MaruForkId(Bytes32.ZERO, UInt64.ZERO)

class MaruForkId(
  val genesisHash: Bytes32,
  val nextForkTimestamp: UInt64 = UInt64.ZERO,
) {
  companion object {
    val MARU_INITIAL_FORK_ID =
      MaruForkId(
        Bytes32.fromHexString("0x112233445566778899aabbccddeeff00112233445566778899aabbccddeeff00"),
        UInt64.ZERO,
      )
    val MARU_FORK_ID_FIELD_NAME = "mfid"

    fun fromNodeRecord(nodeRecord: NodeRecord): Optional<MaruForkId> {
      if (nodeRecord.get(MARU_FORK_ID_FIELD_NAME) != null) {
        return Optional.of(decode(nodeRecord.get(MARU_FORK_ID_FIELD_NAME) as Bytes))
      } else {
        return Optional.empty<MaruForkId>()
      }
    }

    fun decode(encodedForkId: Bytes): MaruForkId {
      require(encodedForkId.size() == 40) { "Invalid encoded MaruForkId length: ${encodedForkId.size()}" }
      val genesisHash = Bytes32.wrap(encodedForkId.slice(0, 32).toArrayUnsafe())
      val nextForkTimestamp = UInt64.fromBytes(encodedForkId.slice(32))
      return MaruForkId(genesisHash, nextForkTimestamp)
    }
  }

  fun encode(): Bytes = Bytes.wrap(genesisHash.toArrayUnsafe() + nextForkTimestamp.toBytes().toArrayUnsafe())
}
