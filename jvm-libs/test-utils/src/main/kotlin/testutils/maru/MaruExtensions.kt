/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package testutils.maru

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import maru.app.MaruApp
import org.awaitility.Awaitility.await
import org.awaitility.core.ConditionFactory

fun MaruApp.awaitTillMaruHasPeers(
  numberOfPeers: UInt,
  await: ConditionFactory = await(),
  timeout: Duration? = null,
) {
  if (timeout == null) {
    await.until { this.peersConnected() >= numberOfPeers }
  } else {
    await
      .timeout(timeout.toJavaDuration())
      .pollInterval(1.seconds.toJavaDuration())
      .until { this.peersConnected() >= numberOfPeers }
  }
}
