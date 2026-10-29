# First upstream baseline

Exact source base 25efb1996bd79e59e6eef28d2d44d9a0405fdd78; no implementation changes.
Gradle selected TCP regression. Three failure cases each failed on assertion: Transport close must terminate the owned acceptor group after listener / connection / listener-throw. Expected true, observed false. Failure identity assertions passed before each cleanup assertion.
Success case verified product behavior, then fixture cleanup reclosed an already closed channel and raised RejectedExecutionException at test line 84. This was a new test fixture issue; corrected cleanup to avoid closing already closed channels. Existing main tests remain unedited. Deferred-completion case was added during compilation and was not in this first compiled register; second baseline includes it and QUIC.
Complete native XML and complete Gradle log retained. No retries configured by Gradle.
