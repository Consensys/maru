/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.config.decoders

import com.sksamuel.hoplite.ConfigFailure
import com.sksamuel.hoplite.ConfigResult
import com.sksamuel.hoplite.DecoderContext
import com.sksamuel.hoplite.Node
import com.sksamuel.hoplite.StringNode
import com.sksamuel.hoplite.decoder.Decoder
import com.sksamuel.hoplite.fp.invalid
import com.sksamuel.hoplite.fp.valid
import kotlin.reflect.KType
import maru.config.SyncingConfig.SyncTargetSelectionConfig

class TomlSyncTargetSelectorTypeDecoder : Decoder<SyncTargetSelectionConfig.SyncTargetSelectorType> {
  override fun decode(
    node: Node,
    type: KType,
    context: DecoderContext,
  ): ConfigResult<SyncTargetSelectionConfig.SyncTargetSelectorType> =
    when (node) {
      is StringNode ->
        runCatching {
          SyncTargetSelectionConfig.SyncTargetSelectorType.valueOfIgnoreCase(node.value.lowercase())
        }.fold(
          { it.valid() },
          { ConfigFailure.DecodeError(node, type).invalid() },
        )

      else -> {
        ConfigFailure.DecodeError(node, type).invalid()
      }
    }

  override fun supports(type: KType): Boolean =
    type.classifier == SyncTargetSelectionConfig.SyncTargetSelectorType::class
}
