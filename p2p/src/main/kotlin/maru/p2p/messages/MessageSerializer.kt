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
package maru.p2p.messages

import maru.core.SealedBeaconBlock
import maru.p2p.Message
import maru.p2p.MessageType
import maru.p2p.Version
import maru.serialization.Serializer

class MessageSerializer(
  private val sealedBeaconBlockSerializer: Serializer<SealedBeaconBlock>,
  private val statusSerializer: Serializer<Status>,
) : Serializer<Message<*>> {
  override fun serialize(message: Message<*>): ByteArray =
    when (message.type) {
      MessageType.QBFT -> TODO()
      MessageType.BEACON_BLOCK -> {
        require(message.payload is SealedBeaconBlock)
        sealedBeaconBlockSerializer.serialize(message.payload as SealedBeaconBlock)
      }
      MessageType.STATUS -> {
        require(message.payload is Status)
        statusSerializer.serialize(message.payload as Status)
      }
    }

  override fun deserialize(bytes: ByteArray): Message<Any> {
    // TODO this isn't going to work for other types
    return Message(MessageType.STATUS, Version.V1, statusSerializer.deserialize(bytes))
  }
}
