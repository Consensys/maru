/*
 * Copyright Consensys Software Inc.
 *
 * This file is dual-licensed under either the MIT license or Apache License 2.0.
 * See the LICENSE-MIT and LICENSE-APACHE files in the repository root for details.
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package maru.app

import kotlin.system.exitProcess
import org.apache.logging.log4j.LogManager
import picocli.CommandLine

object CliEntrypoint {
  private val log = LogManager.getLogger(this.javaClass)

  @JvmStatic
  fun main(args: Array<String>) {
    val cmd = CommandLine(MaruAppCli())
    cmd.isCaseInsensitiveEnumValuesAllowed = true
    cmd.setExecutionExceptionHandler { ex, _, _ ->
      log.error("Execution failure: ", ex)
      1
    }
    cmd.setParameterExceptionHandler { ex, _ ->
      log.error("Invalid args!: ", ex)
      1
    }
    val exitCode = cmd.execute(*args)
    if (exitCode != 0) {
      exitProcess(exitCode)
    }
  }
}
