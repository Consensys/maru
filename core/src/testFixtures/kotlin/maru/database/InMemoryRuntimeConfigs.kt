/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.database

class InMemoryRuntimeConfigs : RuntimeConfigs {
  companion object {
    const val DISCOVERY_SEQUENCE_NUMBER_KEY = "DiscoverySequenceNumber"
  }

  private val configs = mutableMapOf<String, Any>()

  override fun getDiscoverySequenceNumber(): ULong = (configs[DISCOVERY_SEQUENCE_NUMBER_KEY] ?: 0uL) as ULong

  override fun newRuntimeConfigsUpdater(): RuntimeConfigs.Updater = InMemoryRuntimeConfigsUpdater(this)

  override fun close() {
    // No-op for in-memory runtime configs
  }

  class InMemoryRuntimeConfigsUpdater(
    val inMemoryRuntimeConfigs: InMemoryRuntimeConfigs,
  ) : RuntimeConfigs.Updater {
    private val configUpdates = mutableMapOf<String, Any>()

    override fun putDiscoverySequenceNumber(newSequenceNumber: ULong): RuntimeConfigs.Updater {
      configUpdates[InMemoryRuntimeConfigs.DISCOVERY_SEQUENCE_NUMBER_KEY] = newSequenceNumber
      return this
    }

    override fun commit() {
      configUpdates.forEach { (key, value) ->
        inMemoryRuntimeConfigs.configs[key] = value
      }
      configUpdates.clear()
    }

    override fun rollback() {
      configUpdates.clear()
    }

    override fun close() {
      // No-op for in-memory updater
    }
  }
}
