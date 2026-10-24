| Converter state | Existing coverage | Added coverage |
|---|---|---|
| Completed before attachment | CompletedNettyFutureConversionTest: channel/generic success, failure and cancellation on full/live and terminated executors | Original tests unchanged |
| Completed between attachment and dispatch | No explicit post-return held-dispatch case | NettyFutureConversionStateTest: six outcomes queued behind a latch; four failure/cancel outcomes after actual attachment before registration returns |
| Dispatch rejected after completion | Existing full-queue/terminated and registration-success controls | Four after-attachment failure/cancel cases; removing post-attach checks gives four assertion failures |
| Executor shuts down after attachment | No explicit accepted-notification drain case | Six queued outcomes drain during graceful shutdown, preserving exact states/causes |

Supplemental coverage: PendingNettyFutureConversionTest adds four healthy-executor late failure/cancel cases. Four caller-ownership cases check that completing/cancelling the returned future does not mutate its Netty source; generic queued success also covers null. These use real executors/promises and public conversion APIs. The shutdown row covers notifications accepted before shutdown and drained by it; pending completion first attempted only after executor termination continues to depend on Netty's ordinary listener-executor contract, as stated in README.
