package chaos

import java.nio.file.Files
import java.nio.file.Path
import kotlin.collections.ifEmpty
import kotlin.text.isNotBlank
import kotlin.text.trim

object SetupHelper {
  /**
   *  Parses a file with following format:
   *  label = url
   *
   *  besu-follower-0 = http://127.0.0.1:18545
   *  besu-follower-1 = http://127.0.0.1:28545
   *  besu-sequencer-0 = http://127.0.0.1:58545
   */
  fun getNodesUrlsFromFile(filePath: Path): List<NodeInfo<String>> {
    require(Files.exists(filePath)) { "file does not exist: $filePath" }
    return parseLines(
      Files
        .readAllLines(filePath)
    )
      .ifEmpty { throw IllegalStateException("No valid URLs found in file: $filePath") }
  }

  fun parseLines(lines: List<String>): List<NodeInfo<String>> =
    lines
      .filter { it.isNotBlank() }
      .map {
        it.trim()
          .split("=")
          .map { it.trim() }
          .filter { it.isNotBlank() }
          .let { tuple ->
            if (tuple.size == 1) {
              NodeInfo(tuple.first(), tuple.first())
            } else {
              NodeInfo(tuple.first(), tuple[1])
            }
          }
      }
}
