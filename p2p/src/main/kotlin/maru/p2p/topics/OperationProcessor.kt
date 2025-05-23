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
package maru.p2p.topics

import io.libp2p.core.pubsub.ValidationResult
import tech.pegasys.teku.infrastructure.async.SafeFuture
import tech.pegasys.teku.infrastructure.unsigned.UInt64

fun interface OperationProcessor<T> {
  /**
   * Process operation
   *
   * @param operation Operation
   * @param arrivalTimestamp arrival timestamp if operation is received from remote (not necessarily
   * provided). We may consider adding meta container when we need more than only arrival time
   * alongside, but for memory saving and module imports simplicity let's delay it until at
   * least 2 fields are needed.
   * @return result of operation validation
   */
  fun process(
    operation: T,
    arrivalTimestamp: UInt64?,
  ): SafeFuture<ValidationResult>

  companion object {
    val NOOP: OperationProcessor<*> =
      OperationProcessor { _: Any?, _: UInt64? ->
        SafeFuture.completedFuture<ValidationResult>(ValidationResult.Valid)
      }
  }
}
