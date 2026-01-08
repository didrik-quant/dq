package com.didrikquant.core

public class BotFatalException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
