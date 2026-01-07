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
        "fills" -> FillsCommand(dataDir).run()
        "book" -> {
            val timestampArg = args.indexOfFirst { it == "--at" }
            if (timestampArg == -1 || timestampArg + 1 >= args.size) {
                println("Usage: dq book --at <timestamp>")
                return
            }
            val timestamp = args[timestampArg + 1].toLongOrNull()
            if (timestamp == null) {
                println("Invalid timestamp: ${args[timestampArg + 1]}")
                return
            }
            BookCommand(dataDir).run(timestamp)
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
          fills              List fills from last epoch
          book --at <ts>     Show order book at timestamp (epoch millis)
        
        Environment:
          REPLAY_DATA_DIR    Directory for recorded events (default: ~/.dq/recordings)
        """.trimIndent(),
    )
}
