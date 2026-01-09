package com.didrikquant.orderstate

import java.math.BigDecimal

/**
 * Events that affect order state.
 *
 * Naming follows FIX protocol conventions:
 * - Instruction: Outbound messages sent to exchange (NewOrderSingle, CancelRequest, etc.)
 * - ExecutionReport: Inbound messages from exchange reporting order status/fills
 */
public sealed class OrderStateEvent {
    /** Client order ID - present on all events */
    public abstract val clOrdId: String

    /** Event timestamp in epoch millis */
    public abstract val timestamp: Long

    // ============================================================
    // INSTRUCTIONS (outbound to exchange)
    // ============================================================

    public sealed class Instruction : OrderStateEvent() {

        /** New order instruction (FIX: NewOrderSingle) */
        public data class Create(
            override val clOrdId: String,
            val side: Side,
            val price: BigDecimal,
            val qty: BigDecimal,
            override val timestamp: Long,
        ) : Instruction()

        /** Cancel order instruction (FIX: OrderCancelRequest) */
        public data class Cancel(
            override val clOrdId: String,
            override val timestamp: Long,
        ) : Instruction()

        public data class Amend(
            override val clOrdId: String,
            val newPrice: BigDecimal?,
            val newQty: BigDecimal?,
            override val timestamp: Long,
        ) : Instruction()
    }

    // ============================================================
    // EXECUTION REPORTS (inbound from exchange)
    // ============================================================

    public sealed class ExecutionReport : OrderStateEvent() {
        /** Exchange order ID (assigned by exchange) */
        public abstract val orderId: String

        /** Order received, pending validation (ExecType=PendingNew) */
        public data class PendingNew(
            override val clOrdId: String,
            override val orderId: String,
            override val timestamp: Long,
        ) : ExecutionReport()

        /** Order accepted and live on book (ExecType=New) */
        public data class Accepted(
            override val clOrdId: String,
            override val orderId: String,
            val side: Side,
            val price: BigDecimal,
            val qty: BigDecimal,
            override val timestamp: Long,
        ) : ExecutionReport()

        /** Trade execution (ExecType=Trade) - raw fill, quantities computed by OrderStore */
        public data class Trade(
            override val clOrdId: String,
            override val orderId: String,
            val execId: String,
            val fillQty: BigDecimal,
            val fillPrice: BigDecimal,
            override val timestamp: Long,
        ) : ExecutionReport()

        /** Partial fill received (ExecType=Trade, OrdStatus=PartiallyFilled) */
        public data class PartialFill(
            override val clOrdId: String,
            override val orderId: String,
            val execId: String,
            val fillQty: BigDecimal,
            val fillPrice: BigDecimal,
            val cumQty: BigDecimal,
            val leavesQty: BigDecimal,
            override val timestamp: Long,
        ) : ExecutionReport()

        /** Order fully filled - terminal (ExecType=Trade, OrdStatus=Filled) */
        public data class Filled(
            override val clOrdId: String,
            override val orderId: String,
            val execId: String,
            val fillQty: BigDecimal,
            val fillPrice: BigDecimal,
            val cumQty: BigDecimal,
            override val timestamp: Long,
        ) : ExecutionReport()

        /** Order canceled - terminal (ExecType=Canceled) */
        public data class Canceled(
            override val clOrdId: String,
            override val orderId: String,
            val reason: String,
            override val timestamp: Long,
        ) : ExecutionReport()

        /** Order rejected - terminal (ExecType=Rejected) */
        public data class Rejected(
            override val clOrdId: String,
            override val orderId: String,
            val reason: String,
            override val timestamp: Long,
        ) : ExecutionReport() {
            public companion object {
                /** Create a Rejected event when orderId is not yet known (rejected before acceptance) */
                public fun beforeAcceptance(
                    clOrdId: String,
                    reason: String,
                    timestamp: Long,
                ): Rejected = Rejected(clOrdId, "", reason, timestamp)
            }
        }

        /** Order expired - terminal (ExecType=Expired) */
        public data class Expired(
            override val clOrdId: String,
            override val orderId: String,
            override val timestamp: Long,
        ) : ExecutionReport()

        public data class Amended(
            override val clOrdId: String,
            override val orderId: String,
            val previousOrderId: String,
            val newPrice: BigDecimal?,
            val newQty: BigDecimal?,
            override val timestamp: Long,
        ) : ExecutionReport()

        /** Order restated by exchange engine (ExecType=Restated) - margin maintenance, etc. */
        public data class Restated(
            override val clOrdId: String,
            override val orderId: String,
            val reason: RestateReason,
            val newPrice: BigDecimal?,
            val newQty: BigDecimal?,
            override val timestamp: Long,
        ) : ExecutionReport()
    }
}
