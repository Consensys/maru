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

import java.security.MessageDigest
import java.util.Optional
import org.apache.tuweni.bytes.Bytes
import org.apache.tuweni.bytes.Bytes32
import tech.pegasys.teku.infrastructure.unsigned.UInt64
import tech.pegasys.teku.networking.p2p.gossip.PreparedGossipMessage

class MaruPreparedGossipMessage(
  private val origMessage: Bytes,
  private val arrTimestamp: Optional<UInt64>,
  private val domain: String,
  private val topicId: String,
) : PreparedGossipMessage {
  companion object {
    fun shortShaHash(inputData: Bytes): Bytes = sha256(inputData).slice(0, 20)

    private fun sha256(input: Bytes): Bytes32 {
      val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
      input.update(digest)
      return Bytes32.wrap(digest.digest())
    }
  }

  // It's for deduplication of messages
  override fun getMessageId(): Bytes =
    shortShaHash(
      Bytes.wrap(
        origMessage,
        Bytes.wrap(domain.toByteArray()),
        Bytes.wrap(
          topicId
            .toByteArray(),
        ),
      ),
    )

  override fun getDecodedMessage(): PreparedGossipMessage.DecodedMessageResult =
    PreparedGossipMessage.DecodedMessageResult.successful(origMessage)

  override fun getOriginalMessage(): Bytes = origMessage

  override fun getArrivalTimestamp(): Optional<UInt64> = arrTimestamp
}
