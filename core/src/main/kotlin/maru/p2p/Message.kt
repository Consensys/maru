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

enum class Version : Comparable<Version> {
  V1,
}

enum class GossipMessageType : BaseMessageType<GossipMessageType> {
  QBFT,
  BEACON_BLOCK,
  ;

  override fun type(): String = name
}

enum class RpcMessageType : BaseMessageType<RpcMessageType> {
  STATUS(),
  ;

  override fun type(): String = name
}

interface BaseMessageType<TMessageType> {
  fun type(): String
}

data class Message<TPayload, TMessageType>(
  val type: BaseMessageType<TMessageType>,
  val version: Version = Version.V1,
  val payload: TPayload,
)

fun BaseMessageType<GossipMessageType>.toEnum(): GossipMessageType =
  if (type() == GossipMessageType.BEACON_BLOCK.name) {
    GossipMessageType.BEACON_BLOCK
  } else if (type() == GossipMessageType.QBFT.name) {
    GossipMessageType.QBFT
  } else {
    throw IllegalArgumentException("Unsupported message type: ${type()}")
  }

fun BaseMessageType<RpcMessageType>.toEnum(): RpcMessageType {
  if (type() == RpcMessageType.STATUS.name) {
    return RpcMessageType.STATUS
  } else {
    throw IllegalArgumentException("Unsupported message type: ${type()}")
  }
}

interface MessageIdGenerator {
  fun id(
    messageType: BaseMessageType<*>,
    version: Version,
  ): String
}

class LineaMessageIdGenerator(
  private val chainId: UInt,
) : MessageIdGenerator {
  override fun id(
    messageType: BaseMessageType<*>,
    version: Version,
  ): String = "/linea/$chainId/${messageType.type().lowercase()}/$version"
}

class LineaRpcProtocolIdGenerator(
  private val chainId: UInt,
) : MessageIdGenerator {
  override fun id(
    messageType: BaseMessageType<*>,
    version: Version,
  ): String = "/linea/req/$chainId/${messageType.type().lowercase()}/$version"
}
