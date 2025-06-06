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
package maru.p2p

import maru.core.SealedBeaconBlock

enum class Version : Comparable<Version> {
  V1,
}

enum class MessageType {
  QBFT, // Won't be supported until Milestone 6
  BEACON_BLOCK,
  STATUS,
}

data class Message<T>(
  val type: MessageType,
  val version: Version = Version.V1,
  val payload: T,
) {
  init {
    when (type) {
      MessageType.QBFT -> Unit // require(payload is BftMessage≤*>) Not adding this to avoid dependency on QBFT
      MessageType.BEACON_BLOCK -> require(payload is SealedBeaconBlock)
      MessageType.STATUS -> Unit // Not adding this to avoid dependency on P2P
    }
  }
}

interface MessageIdGenerator {
  fun id(
    messageType: MessageType,
    version: Version,
  ): String
}

class LineaMessageIdGenerator(
  private val chainId: UInt,
) : MessageIdGenerator {
  override fun id(
    messageType: MessageType,
    version: Version,
  ): String = "/linea/$chainId/${messageType.toString().lowercase()}/$version"
}

class LineaRpcProtocolIdGenerator(
  private val chainId: UInt,
) : MessageIdGenerator {
  override fun id(
    messageType: MessageType,
    version: Version,
  ): String = "/linea/req/$chainId/${messageType.toString().lowercase()}/$version"
}
