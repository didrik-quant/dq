# Evolution Log - PF_XRPUSD

This file tracks the evolution of AgentXrpStrategy.kt across epochs.

## How This Works

Each epoch:
1. You modify the strategy in `strategy/src/main/kotlin/com/didrikquant/strategy/AgentXrpStrategy.kt`
2. The strategy runs live until 50 trades complete (or 2 hour safety timeout)
3. Results (Sharpe ratio) are appended here

## Strategy File

`strategy/src/main/kotlin/com/didrikquant/strategy/AgentXrpStrategy.kt`

## Epoch 0 - Baseline

### Changes

Initial strategy: copy of SimpleMarketMaker with default parameters:
- spreadBps: 10
- orderSize: 15
- skewFactor: 0.0001
- tickSize: 0.00001

### Results

- Sharpe: (pending first run)

---

---

## Epoch 1 - FAILED at 2026-01-09 21:31 UTC

### Failure: RISK_BREACH

```
com.didrikquant.core.BotFatalException: RISK BREACH: Max open orders reached: 10
	at com.didrikquant.bot.handlers.TradingHandler.processStrategyActions(TradingHandler.kt:177)
	at com.didrikquant.bot.handlers.TradingHandler.processBookEvents(TradingHandler.kt:131)
	at com.didrikquant.bot.handlers.TradingHandler.onEvent(TradingHandler.kt:46)
	at com.didrikquant.bot.handlers.TradingHandler.onEvent(TradingHandler.kt:25)
	at com.lmax.disruptor.BatchEventProcessor.processEvents(BatchEventProcessor.java:167)
	at com.lmax.disruptor.BatchEventProcessor.run(BatchEventProcessor.java:122)
	at java.base/java.lang.Thread.run(Thread.java:1583)
22:31:49.642 [disruptor-2] ERROR com.didrikquant.bot.Pipeline - Fatal error - canceling orders and exiting
com.didrikquant.core.BotFatalException: RISK BREACH: Max open orders reached: 10
	at com.didrikquant.bot.handlers.TradingHandler.processStrategyActions(TradingHandler.kt:177)
	at com.didrikquant.bot.handlers.TradingHandler.processBookEvents(TradingHandler.kt:131)
	at com.didrikquant.bot.handlers.TradingHandler.onEvent(TradingHandler.kt:46)
	at com.didrikquant.bot.handlers.TradingHandler.onEvent(TradingHandler.kt:25)
	at com.lmax.disruptor.BatchEventProcessor.processEvents(BatchEventProcessor.java:167)
```

### Attempted Changes

No changes

---

## Epoch 2 - FAILED at 2026-01-09 22:29 UTC

### Failure: RISK_BREACH

```
com.didrikquant.core.BotFatalException: RISK BREACH: Max open orders reached: 10
	at com.didrikquant.bot.handlers.TradingHandler.processStrategyActions(TradingHandler.kt:177)
	at com.didrikquant.bot.handlers.TradingHandler.processBookEvents(TradingHandler.kt:131)
	at com.didrikquant.bot.handlers.TradingHandler.onEvent(TradingHandler.kt:46)
	at com.didrikquant.bot.handlers.TradingHandler.onEvent(TradingHandler.kt:25)
	at com.lmax.disruptor.BatchEventProcessor.processEvents(BatchEventProcessor.java:167)
	at com.lmax.disruptor.BatchEventProcessor.run(BatchEventProcessor.java:122)
	at java.base/java.lang.Thread.run(Thread.java:1583)
23:29:05.671 [1;31mERROR[0;39m [36mPipeline            [0;39m - Fatal error - canceling orders and exiting
com.didrikquant.core.BotFatalException: RISK BREACH: Max open orders reached: 10
	at com.didrikquant.bot.handlers.TradingHandler.processStrategyActions(TradingHandler.kt:177)
	at com.didrikquant.bot.handlers.TradingHandler.processBookEvents(TradingHandler.kt:131)
	at com.didrikquant.bot.handlers.TradingHandler.onEvent(TradingHandler.kt:46)
	at com.didrikquant.bot.handlers.TradingHandler.onEvent(TradingHandler.kt:25)
	at com.lmax.disruptor.BatchEventProcessor.processEvents(BatchEventProcessor.java:167)
```

### Attempted Changes

No changes

---

## Epoch 3 - FAILED at 2026-01-09 22:48 UTC

### Failure: RISK_BREACH

```
com.didrikquant.core.BotFatalException: RISK BREACH: Max open orders reached: 10
	at com.didrikquant.bot.handlers.TradingHandler.processStrategyActions(TradingHandler.kt:177)
	at com.didrikquant.bot.handlers.TradingHandler.processBookEvents(TradingHandler.kt:131)
	at com.didrikquant.bot.handlers.TradingHandler.onEvent(TradingHandler.kt:46)
	at com.didrikquant.bot.handlers.TradingHandler.onEvent(TradingHandler.kt:25)
	at com.lmax.disruptor.BatchEventProcessor.processEvents(BatchEventProcessor.java:167)
	at com.lmax.disruptor.BatchEventProcessor.run(BatchEventProcessor.java:122)
	at java.base/java.lang.Thread.run(Thread.java:1583)
23:48:19.835 [1;31mERROR[0;39m [36mPipeline            [0;39m - Fatal error - canceling orders and exiting
com.didrikquant.core.BotFatalException: RISK BREACH: Max open orders reached: 10
	at com.didrikquant.bot.handlers.TradingHandler.processStrategyActions(TradingHandler.kt:177)
	at com.didrikquant.bot.handlers.TradingHandler.processBookEvents(TradingHandler.kt:131)
	at com.didrikquant.bot.handlers.TradingHandler.onEvent(TradingHandler.kt:46)
	at com.didrikquant.bot.handlers.TradingHandler.onEvent(TradingHandler.kt:25)
	at com.lmax.disruptor.BatchEventProcessor.processEvents(BatchEventProcessor.java:167)
```

### Attempted Changes

No changes

---

## Epoch 4 - FAILED at 2026-01-09 22:52 UTC

### Failure: RUNTIME_CRASH

```
com.didrikquant.core.BotFatalException: Invalid order transition: Order is in terminal state FILLED, cannot apply Amended(clOrdId=b810c92ba4f242cc97, orderId=a0cc6fc1-f980-491b-9e6b-d48fa25ed3a2, previousOrderId=, newPrice=2.0957, newQty=null, timestamp=1767999160936)
	at com.didrikquant.bot.handlers.TradingHandler.processOrderEvents(TradingHandler.kt:68)
	at com.didrikquant.bot.handlers.TradingHandler.onEvent(TradingHandler.kt:42)
	at com.didrikquant.bot.handlers.TradingHandler.onEvent(TradingHandler.kt:25)
	at com.lmax.disruptor.BatchEventProcessor.processEvents(BatchEventProcessor.java:167)
	at com.lmax.disruptor.BatchEventProcessor.run(BatchEventProcessor.java:122)
	at java.base/java.lang.Thread.run(Thread.java:1583)
23:52:40.944 [1;31mERROR[0;39m [36mPipeline            [0;39m - Fatal error - canceling orders and exiting
com.didrikquant.core.BotFatalException: Invalid order transition: Order is in terminal state FILLED, cannot apply Amended(clOrdId=b810c92ba4f242cc97, orderId=a0cc6fc1-f980-491b-9e6b-d48fa25ed3a2, previousOrderId=, newPrice=2.0957, newQty=null, timestamp=1767999160936)
	at com.didrikquant.bot.handlers.TradingHandler.processOrderEvents(TradingHandler.kt:68)
	at com.didrikquant.bot.handlers.TradingHandler.onEvent(TradingHandler.kt:42)
	at com.didrikquant.bot.handlers.TradingHandler.onEvent(TradingHandler.kt:25)
	at com.lmax.disruptor.BatchEventProcessor.processEvents(BatchEventProcessor.java:167)
	at com.lmax.disruptor.BatchEventProcessor.run(BatchEventProcessor.java:122)
	at java.base/java.lang.Thread.run(Thread.java:1583)
```

### Attempted Changes

No changes

---

## Epoch 5 - FAILED at 2026-01-09 22:59 UTC

### Failure: RUNTIME_CRASH

```
com.didrikquant.core.BotFatalException: Invalid order transition: Order is in terminal state CANCELED, cannot apply Canceled(clOrdId=f7e5f1a1be2545479f, orderId=, reason=user_requested, timestamp=1767999556367)
	at com.didrikquant.bot.handlers.TradingHandler.processOrderEvents(TradingHandler.kt:68)
	at com.didrikquant.bot.handlers.TradingHandler.onEvent(TradingHandler.kt:42)
	at com.didrikquant.bot.handlers.TradingHandler.onEvent(TradingHandler.kt:25)
	at com.lmax.disruptor.BatchEventProcessor.processEvents(BatchEventProcessor.java:167)
	at com.lmax.disruptor.BatchEventProcessor.run(BatchEventProcessor.java:122)
	at java.base/java.lang.Thread.run(Thread.java:1583)
23:59:16.377 [1;31mERROR[0;39m [36mPipeline            [0;39m - Fatal error - canceling orders and exiting
com.didrikquant.core.BotFatalException: Invalid order transition: Order is in terminal state CANCELED, cannot apply Canceled(clOrdId=f7e5f1a1be2545479f, orderId=, reason=user_requested, timestamp=1767999556367)
	at com.didrikquant.bot.handlers.TradingHandler.processOrderEvents(TradingHandler.kt:68)
	at com.didrikquant.bot.handlers.TradingHandler.onEvent(TradingHandler.kt:42)
	at com.didrikquant.bot.handlers.TradingHandler.onEvent(TradingHandler.kt:25)
	at com.lmax.disruptor.BatchEventProcessor.processEvents(BatchEventProcessor.java:167)
	at com.lmax.disruptor.BatchEventProcessor.run(BatchEventProcessor.java:122)
	at java.base/java.lang.Thread.run(Thread.java:1583)
```

### Attempted Changes

No changes
