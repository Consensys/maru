/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.metrics

import java.util.Optional
import net.consensys.linea.metrics.MetricsCategory
import org.hyperledger.besu.plugin.services.metrics.MetricCategory as BesuMetricsCategory

enum class MaruMetricsCategory : MetricsCategory {
  ENGINE_API,
  METADATA,
  P2P_NETWORK,
}

enum class MaruMetricsSystemCategory : BesuMetricsCategory {
  STORAGE {
    override fun getName(): String = "storage"

    override fun getApplicationPrefix(): Optional<String> = Optional.empty()
  },
}
