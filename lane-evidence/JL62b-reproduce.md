# Reproduce the JL62b comparisons

The saved counts and identities are in JL62b-full-counts.json. The tested source commits and classification are in JL62b-classification.md. These helpers do not require the unpublished raw full-summary file.

1. Fresh-clone jvm-libp2p twice under a lane directory, as repo and develop. Check out PR head 091b6d17280045ec2a1260ec890d1a06991bb6ac in repo and baseline bd7edb2570fb609c20245305c25af29d166a67c6 in develop. Read README and project instructions. Fetch/rebase against develop before building; if the source changes, record and test that new head separately.
2. Set the output-root constant in JL62b-compare.py and JL62b-port-controls.py to your evidence directory. Their lane variable expects sibling workspace/JL62b/{repo,develop}; adapt that path as well if needed. Copy JL62b-full-counts.json into the output directory.
3. On each checkout, export its actual test runtime classpath, building all runtime dependencies including test fixtures:

   `./gradlew --no-daemon -I <evidence>/JL62b-classpath.init.gradle :libp2p:jl62bRuntimeClasspath -Pjl62bClasspathFile=<evidence>/JL62b-<pr-or-develop>-classpath.txt`

4. Compile JL62bOneTest.java into an evidence-local JL62b-runner directory, using the exported runtime classpath as javac's classpath. The optional API annotation warnings from this tool compilation do not change test execution. Do not commit the class files.
5. Run `python3 JL62b-compare.py`. It uses one fresh JVM per selected public JUnit invocation and rejects zero/multiple tests, skips and aborts. In this lane a first valid PR parameter report already existed and was reused; for a fresh run no report exists, so it executes that case normally. The script checks the source heads before starting.
6. Run `python3 JL62b-port-controls.py` after the ordinary comparison finishes. It uses an actual listener on 127.0.0.1:40002 to create the full-run OS condition. It checks the exact BindException and closes the listener after each selected test. It does not change the test source or any assertion, iteration count or timeout. These are local test ports, never production endpoints.

Every selected JVM is bounded externally at five minutes; all original in-test waits remain unchanged. There are no retries. The ordinary test failure details are in the per-case XML, and the reviewed results are in the summary JSON files. Do not count the separately retained invalid no-fixtures discovery: it started zero tests.

The full suite was run once under the shared lock with the exact root Gradle task list: `./gradlew --no-daemon cleanTest test --continue`. JL62b-full.sh and JL62b-run-full.py record the tracked wrapper, pre-build rebase, four-hour execution allowance after lock acquisition, and two-minute memory sampler. Do not replace an executing suite with a shorter deadline or rerun it unchanged.
