/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
import maru.app.MaruApp
import org.awaitility.kotlin.await

fun MaruApp.awaitTillMaruHasPeers(numberOfPeers: UInt) {
  await.until { this.peersConnected() >= numberOfPeers }
}
