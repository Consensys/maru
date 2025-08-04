/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.syncing

import java.util.Timer
import java.util.TimerTask

// Test implementation that allows controlling the timer execution
internal class TestableTimer : Timer("test-timer", true) {
  val scheduledTasks = mutableListOf<TimerTask>()
  val delays = mutableListOf<Long>()
  val periods = mutableListOf<Long>()

  override fun scheduleAtFixedRate(
    task: TimerTask,
    delay: Long,
    period: Long,
  ) {
    scheduledTasks.add(task)
    delays.add(delay)
    periods.add(period)
  }

  fun runNextTask() {
    if (scheduledTasks.isNotEmpty()) {
      scheduledTasks[0].run()
    }
  }

  override fun cancel() {
    super.cancel()
    scheduledTasks.clear()
    delays.clear()
    periods.clear()
  }
}
