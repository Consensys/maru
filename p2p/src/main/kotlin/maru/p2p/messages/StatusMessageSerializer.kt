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

import maru.p2p.Message
import maru.p2p.RpcMessageType
import maru.p2p.Version
import maru.serialization.Serializer

class StatusMessageSerializer(
  private val statusSerializer: Serializer<Status>,
) : Serializer<Message<Status, RpcMessageType>> {
  override fun serialize(message: Message<Status, RpcMessageType>): ByteArray =
    statusSerializer.serialize(message.payload)

  override fun deserialize(bytes: ByteArray): Message<Status, RpcMessageType> =
    Message(RpcMessageType.STATUS, Version.V1, statusSerializer.deserialize(bytes))
}
