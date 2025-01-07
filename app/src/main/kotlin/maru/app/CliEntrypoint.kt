package maru.app

import kotlin.system.exitProcess
import org.apache.logging.log4j.LogManager
import picocli.CommandLine

object CliEntrypoint {
  private val log = LogManager.getLogger(this.javaClass)

  @JvmStatic
  fun main(args: Array<String>) {
    val cmd = CommandLine(MaruAppCli())
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
