package com.didrikquant.cli

import java.nio.file.Path

public fun main(args: Array<String>) {
    if (args.isEmpty()) {
        printUsage()
        return
    }

    val dataDir = Path.of(
        System.getenv("REPLAY_DATA_DIR") ?: "${System.getProperty("user.home")}/.dq/recordings",
    )

    when (args[0]) {
        "epoch" -> {
            val fromIndex = args.indexOfFirst { it == "--from" }
            val toIndex = args.indexOfFirst { it == "--to" }

            if (fromIndex == -1 || fromIndex + 1 >= args.size) {
                println("Error: --from <timestamp> is required")
                printUsage()
                return
            }
            if (toIndex == -1 || toIndex + 1 >= args.size) {
                println("Error: --to <timestamp> is required")
                printUsage()
                return
            }

            val fromTimestamp = args[fromIndex + 1].toLongOrNull()
            val toTimestamp = args[toIndex + 1].toLongOrNull()

            if (fromTimestamp == null) {
                println("Invalid --from timestamp: ${args[fromIndex + 1]}")
                return
            }
            if (toTimestamp == null) {
                println("Invalid --to timestamp: ${args[toIndex + 1]}")
                return
            }

            val compareFromIndex = args.indexOfFirst { it == "--compare-from" }
            val compareToIndex = args.indexOfFirst { it == "--compare-to" }

            val compareFromTimestamp = if (compareFromIndex != -1 && compareFromIndex + 1 < args.size) {
                args[compareFromIndex + 1].toLongOrNull()
            } else {
                null
            }

            val compareToTimestamp = if (compareToIndex != -1 && compareToIndex + 1 < args.size) {
                args[compareToIndex + 1].toLongOrNull()
            } else {
                null
            }

            EpochCommand(dataDir).run(
                fromTimestamp = fromTimestamp,
                toTimestamp = toTimestamp,
                compareFromTimestamp = compareFromTimestamp,
                compareToTimestamp = compareToTimestamp,
            )
        }
        else -> {
            println("Unknown command: ${args[0]}")
            printUsage()
        }
    }
}

private fun printUsage() {
    println(
        """
        Usage: dq <command> [options]

        Commands:
          epoch --from <ts> --to <ts>     Analyze epoch metrics
                [--compare-from <ts> --compare-to <ts>]

        Timestamps are epoch milliseconds.

        Environment:
          REPLAY_DATA_DIR    Directory for recorded events (default: ~/.dq/recordings)
        """.trimIndent(),
    )
}
