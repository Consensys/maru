/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
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
  when (type()) {
    GossipMessageType.BEACON_BLOCK.name -> GossipMessageType.BEACON_BLOCK
    GossipMessageType.QBFT.name -> GossipMessageType.QBFT
    else -> throw IllegalArgumentException("Unsupported message type: ${type()}")
  }

fun BaseMessageType<RpcMessageType>.toEnum(): RpcMessageType =
  if (type() == RpcMessageType.STATUS.name) {
    RpcMessageType.STATUS
  } else {
    throw IllegalArgumentException("Unsupported message type: ${type()}")
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
