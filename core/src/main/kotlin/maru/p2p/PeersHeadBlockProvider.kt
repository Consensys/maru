/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.p2p

interface PeersHeadBlockProvider {
  /**
   * Returns a list of peerId and chain head block number
   */
  fun getPeersHeads(): Map<String, ULong>
}
