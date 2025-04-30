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

import io.libp2p.core.pubsub.ValidationResult
import java.util.Optional
import org.apache.tuweni.bytes.Bytes
import tech.pegasys.teku.infrastructure.async.SafeFuture
import tech.pegasys.teku.infrastructure.unsigned.UInt64
import tech.pegasys.teku.networking.p2p.gossip.PreparedGossipMessage
import tech.pegasys.teku.networking.p2p.gossip.TopicHandler

class TestTopicHandler : TopicHandler {
  companion object {
    val dataFuture = SafeFuture<Bytes>()
    private const val ORIGINAL_MESSAGE = "deaddeadbeefbeef"
  }

  override fun prepareMessage(
    // TODO: don't know where / how this is used. Looks like it is never used anywhere
    payload: Bytes?,
    arrivalTimestamp: Optional<UInt64>?,
  ): PreparedGossipMessage = MaruPreparedGossipMessage(Bytes.fromHexString("deadbaaf"), Optional.empty())

  override fun handleMessage(message: PreparedGossipMessage?): SafeFuture<ValidationResult> {
    var data: Bytes?
    message.let {
      data = message!!.originalMessage
    }
    // at this point we have to validate the message (will only be further distributed if valid)
    // at this point we should also (asynchonously) do what needs to be done with the data we received
    dataFuture.complete(data)
    return if (data!!.equals(Bytes.fromHexString(ORIGINAL_MESSAGE))) {
      SafeFuture.completedFuture(ValidationResult.Valid)
    } else {
      SafeFuture.completedFuture(ValidationResult.Invalid)
    }
  }

  override fun getMaxMessageSize(): Int = 43434343 // TODO: what is a good max size here? 10MB?
}
