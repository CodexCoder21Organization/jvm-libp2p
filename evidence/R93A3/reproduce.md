OBSERVED: Run from a fresh lane layout with this evidence branch at workspace/jvm-libp2p and detached reviewed source at workspace/pr-head. Controls and compiled outputs are outside the Git tree. The compiler/console scripts derive the lane root from their evidence path. No product file is edited. No extra model is needed.

OBSERVED: The successful compile and exact-head gate used /opt/java, Gradle --no-daemon --max-workers=1, in-process Kotlin compilation, 1 test fork and 3 reserved JVM slots. The full local command selected all 39 touched classes from git diff origin/develop...HEAD. gradle-control.gradle routes XML with -Dr93t.xml=<lane>/local-xml; classpath.gradle exports classpaths with -Df93h.output=<lane>/build-cp and :libp2p:f93hClasspath. JUnit console standalone is 1.10.2 from Maven Central. No new JVM tuning flags were used.

OBSERVED: After exporting the exact-head test runtime and compiler classpaths, the actual probe commands, from workspace/jvm-libp2p, are:

```bash
python3 evidence/R93A3/compile-overlay.py ordering-classes workspace/jvm-libp2p evidence/R93A3/R93A3HeldCloseOrderingTest.kt
python3 evidence/R93A3/compile-overlay.py reentrant-ordering-classes workspace/jvm-libp2p evidence/R93A3/R93A3ReentrantHeldCloseOrderingTest.kt
python3 evidence/R93A3/run-console.py ordering-head ordering-classes R93A3HeldCloseOrderingTest
python3 evidence/R93A3/run-console.py reentrant-ordering-head reentrant-ordering-classes R93A3ReentrantHeldCloseOrderingTest
```

OBSERVED: For the old-promise comparison, create <lane>/controls/old-promise and export NettyChannelClose.kt from commit a32b7345845f08c1b445762f96b4d1579d76f70d at libp2p/src/main/kotlin/io/libp2p/transport/implementation/NettyChannelClose.kt into that directory. The complete control hash is in controls.json; promise-change.patch shows its difference from the reviewed helper. Run:

```bash
python3 evidence/R93A3/compile-overlay.py old-promise-classes controls/old-promise NettyChannelClose.kt
python3 evidence/R93A3/run-console.py ordering-old-promise old-promise-classes:ordering-classes R93A3HeldCloseOrderingTest
python3 evidence/R93A3/run-console.py reentrant-ordering-old-promise old-promise-classes:reentrant-ordering-classes R93A3ReentrantHeldCloseOrderingTest
```

OBSERVED: For the intended F93m regressions, export the same helper from commit 540d17af7343e709fcbf42d67ac6da81f179efd0 to <lane>/controls/pre-f93m, then run:

```bash
python3 evidence/R93A3/compile-overlay.py pre-f93m-classes controls/pre-f93m NettyChannelClose.kt
python3 evidence/R93A3/run-console.py f93m-pre-fix pre-f93m-classes F93mPublishedCloseFailureTest F93mQuicDestroyedLoopReleaseTest
```

OBSERVED: These are single console executions with 0 retries. The 2 F93m current greens are in the 64-case local touched register. No red probe is run against a production endpoint. Both R1 probes start their own real loopback hosts, hold an original public outbound close promise, and force Netty's notification-depth condition with 8 public DefaultPromise callbacks. The explicit reentrant case observes Connection.closeFuture, re-enters Connection.close and checks the whole event order after a loop barrier. This is an ordering condition, not machine-calibrated load.
