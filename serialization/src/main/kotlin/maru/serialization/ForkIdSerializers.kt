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
package maru.serialization

import java.nio.ByteBuffer
import maru.config.consensus.qbft.QbftConsensusConfig
import maru.consensus.ForkId
import maru.consensus.ForkSpec
import maru.extensions.encodeHex

object ForkIdSerializers {
  object QbftConsensusConfigSerializer : Serializer<QbftConsensusConfig> {
    override fun serialize(value: QbftConsensusConfig): ByteArray {
      // Sort validators deterministically by address hex
      val validatorsSorted = value.validatorSet.sortedBy { it.address.encodeHex(prefix = false) }
      // Allocate buffer: 20 bytes per validator + 4 for elFork.ordinal
      val buffer = ByteBuffer.allocate(validatorsSorted.size * 20 + 4)
      for (validator in validatorsSorted) {
        buffer.put(validator.address)
      }
      buffer.putInt(value.elFork.ordinal)
      return buffer.array()
    }
  }

  object ForkSpecSerializer : Serializer<ForkSpec> {
    override fun serialize(value: ForkSpec): ByteArray =
      when (value.configuration) {
        is QbftConsensusConfig -> {
          val serializedConsensusConfig =
            QbftConsensusConfigSerializer.serialize(value.configuration as QbftConsensusConfig)
          ByteBuffer
            .allocate(4 + 8 + serializedConsensusConfig.size)
            .putInt(value.blockTimeSeconds)
            .putLong(value.timestampSeconds)
            .put(serializedConsensusConfig)
            .array()
        }

        else -> throw IllegalArgumentException("${value.configuration.javaClass.simpleName} is not supported!")
      }
  }

  object ForkIdSerializer : Serializer<ForkId> {
    override fun serialize(value: ForkId): ByteArray {
      val serializedForkSpec = ForkSpecSerializer.serialize(value.forkSpec)

      val buffer =
        ByteBuffer
          .allocate(4 + serializedForkSpec.size + 32)
          .putInt(value.chainId.toInt())
          .put(serializedForkSpec)
          .put(value.genesisRootHash)

      return buffer.array()
    }
  }
}
