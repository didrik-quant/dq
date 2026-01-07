package com.didrikquant.harness

public fun main() {
    val config = HarnessConfig.load()
    val harness = Harness(config)
    harness.run()
}
