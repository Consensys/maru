/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
import maru.core.Protocol

interface ELSyncPipeline : Protocol {
  fun setELHead(head: ByteArray)

  fun isELSynced(): Boolean

  fun setOnELSyncCompleteHandler(handler: (head: ByteArray) -> Unit)
}
