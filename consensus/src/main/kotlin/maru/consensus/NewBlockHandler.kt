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
package maru.consensus

import org.apache.logging.log4j.LogManager
import org.hyperledger.besu.ethereum.core.Block

fun interface NewBlockHandler {
  fun handleNewBlock(block: Block)
}

class NewBlockHandlerMultiplexer(
  private val list: List<Pair<String, NewBlockHandler>>,
) : NewBlockHandler {
  private val log = LogManager.getLogger(NewBlockHandlerMultiplexer::class.java)!!

  override fun handleNewBlock(block: Block) {
    list.forEach {
      val (handlerName, handler) = it
      try {
        handler.handleNewBlock(block)
      } catch (ex: Exception) {
        log.error(
          "New block handler $handlerName failed processing" +
            " block hash=${block.hash}, number=${block.header.number}!",
          ex,
        )
      }
    }
  }
}
