from pathlib import Path
import json
r=Path(__file__).resolve().parent
b=json.loads((r/'pr-before-patch.json').read_text())['body']
a=b.index('Every edit to a test that already existed at the start of this round:');z=b.index('`NoiseMatcherReadinessTest.kt` is new in this round.',a)
b=b[:a]+'''The older per-file fixture-change table is retained in the [previous description](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/F68g-20261024/.review-round8/previous-pr-description.md). Its provider, destruction, thread, error, public-entry, Ping, callback and cleanup checks remain; round6 restores the aggregate bound above.

'''+b[z:]
footer='🤖 Generated with [Claude Code](https://claude.com/claude-code)'
assert b.rstrip().endswith(footer);b=b.rstrip()[:-len(footer)].rstrip()
section='''

## Review round 8

The owner requested a smaller fixture within the unchanged 30-second bound, proved under eight-way load. The earlier unfinished step remains unknown; no production cause is claimed.

[JFR is replaced by an in-memory class-load observer](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/4fd50c8e49960525c3ddae1397e5b985e0a5ec3c), using a tiny test-only Java agent. Class bytes stay unchanged. The same first-stream interval, exact class predicate, zero-load assertion/error, provider first-use/destruction/off-Netty/no-connections checks and real Ping remain. A positive preparation observation prevents an absent observer passing zero. Production is unchanged.

| Wrapped WebSocket child | READY ms | BODY ms | CLEANUP ms | Sum before exit ms |
|---|---:|---:|---:|---:|
| JFR alone |3098|3568|30|6696|
| JFR eight-way worst |11372|9011|29|20412|
| Final alone |1102|1474|10|2586|
| Final eight-way worst |6207|9220|20|15447|

Final eight-way: 24/24 first attempt; all 8 exit codes zero; 14,553 ms aggregate margin. Same round 7 harness: one native Gradle class and seven independent JUnit Launcher classes. All children exited within 30 seconds. JFR start/dump/parse was 856 ms of 9,011 ms BODY; the 10,269 ms constructor-done marker is pre-READY and includes earlier fixture work.

File logging was rejected: 22/24 passed; both WS-only failures are retained. One stopped before negotiation (READY 15170 ms, BODY pending 14830 ms); one expired at EXIT (READY 13490, BODY 15057, CLEANUP 94, EXIT pending 1358 ms). No unchanged retry replaced them.

Native class results, each first attempt:

| Class | Pass | ms |
|---|---:|---:|
'''
for row in json.loads((r/'final-register.json').read_text()):section+=f"|{row['class']}|{row['tests']}/{row['tests']}|{row['ms']}|\n"
section+='''
43/43, zero skips/errors. After the final private JAR-scope edit, both affected callers were rechecked; the other nine paths are unchanged. Spotless passed. Real class-definition controls: inside interval fails with exactly 1 selected load; before/after pass; omitted agent fails its positive check. Production-only reversals still fail at runtime: Traffic 4/4 “Noise preparation did not request the scoped digest”; Binding 3/3 “Host returned before the real SHA-256 provider's first use”. Source/bytecode restored; all owned processes stopped.

[Full records, native XML, failure stacks and diagnostic source](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/F68g-20261024/.review-round8/findings.md). No assertion, bound or iteration count was weakened.
'''
b=b+section+'\n'+footer+'\n'
print('CHARS',len(b),'BYTES',len(b.encode()))
assert len(b.encode())<=65536
assert b.startswith(json.loads((r/'pr-initial.json').read_text())['body'].split('\n\n')[0])
(r/'body.json').write_text(json.dumps({'body':b},ensure_ascii=False))
(r/'proposed-description.md').write_text(b)
