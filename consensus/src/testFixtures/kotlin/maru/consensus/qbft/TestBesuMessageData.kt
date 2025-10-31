/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.consensus.qbft

import org.apache.tuweni.bytes.Bytes
import org.hyperledger.besu.ethereum.p2p.rlpx.wire.MessageData

/**
 * Test implementation of BesuMessageData for unit testing.
 * Implements equals and hashCode for proper comparison in tests.
 */
data class TestBesuMessageData(
  private val code: Int,
  private val data: Bytes,
) : MessageData {
  override fun getData(): Bytes = data

  override fun getSize(): Int = data.size()

  override fun getCode(): Int = code
}
