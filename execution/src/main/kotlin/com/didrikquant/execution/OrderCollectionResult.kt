package com.didrikquant.execution

import com.didrikquant.orderstate.OrderSnapshot

public sealed class CreateResult {
    public data class Created(val snapshot: OrderSnapshot) : CreateResult()
    public data class DuplicateClOrdId(val existing: OrderSnapshot) : CreateResult()
}

public sealed class ApplyResult {
    public data class Success(
        val before: OrderSnapshot,
        val after: OrderSnapshot,
    ) : ApplyResult()

    public data class SuccessWithWarning(
        val before: OrderSnapshot,
        val after: OrderSnapshot,
        val warning: String,
    ) : ApplyResult()

    public data class OrderNotFound(val clOrdId: String) : ApplyResult()

    public data class InvalidTransition(
        val snapshot: OrderSnapshot,
        val reason: String,
    ) : ApplyResult()

    public data class DuplicateExecution(
        val snapshot: OrderSnapshot,
        val execId: String,
    ) : ApplyResult()
}
