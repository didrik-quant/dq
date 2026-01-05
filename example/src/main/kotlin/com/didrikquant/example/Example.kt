package com.didrikquant.example

/**
 * Example class demonstrating the Kotlin setup.
 *
 * This is a minimal example to verify the Bazel + Kotlin configuration works.
 */
public class Example {
    /**
     * Returns a greeting message.
     *
     * @param name The name to greet.
     * @return A greeting string.
     */
    public fun greet(name: String): String {
        return "Hello, $name! Welcome to Didrik Quant."
    }

    public companion object {
        /**
         * Application version.
         */
        public const val VERSION: String = "0.0.1"
    }
}
