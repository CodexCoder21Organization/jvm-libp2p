from pathlib import Path
import subprocess,json,xml.etree.ElementTree as E
out=Path(__file__).resolve().parent
repo=out.parent/'ws/F71b2/jvm-libp2p'
base='https://github.com/CodexCoder21Organization/jvm-libp2p'
evidence=base+'/tree/wip/F71b2-20261024/investigation/F71b2'
blob=base+'/blob/wip/F71b2-20261024/investigation/F71b2/'
def git(*a):return subprocess.check_output(['git',*a],cwd=repo,text=True).strip()
head=git('rev-parse','HEAD')
coverage=git('log','-1','--format=%H','--','libp2p/src/test/kotlin/io/libp2p/etc/types/PendingNettyFutureConversionTest.kt')
doc=git('log','-1','--format=%H','--','FORK.md')
body='''`testRejectedDrainLoggingDoesNotHoldOutboxMonitor` timed out during host stop in the required full-suite check for https://github.com/CodexCoder21Organization/UrlProtocol/pull/635. The first failure is recorded at https://github.com/CodexCoder21Organization/UrlProtocol/actions/runs/40085745929/job/120848195951; the owner requested local reproduction and a fix at its source. This work is part of the owner's request to finish open handoffs; jvm-libp2p is consumed by UrlProtocol.

Handoff: https://www.handoff.wasmserver.com/handoffs/hf-2026-09-24-make-kompile-cli-remote-runs-reliable-resume-chunked-uploads-from-the-failed-chunk-land-the-reconnect-fix-and-deploy-the-server-cpu-fix

A completed Netty promise could lose its conversion callback when its live event-loop queue rejected listener dispatch. The returned Java future then stayed pending after the queue recovered. Both converter overloads copy terminal results directly and recheck completion after listener registration; pending operations continue to use their normal listener executor.

The notification-site sweep also covers completed connection closes and stream writes. External completed closes no longer require accepted notification dispatch, while reentrant close ordering on the owning loop stays intact. Stream writes use the corrected converter. When a parent-write listener cannot enqueue its child completion on a full owning loop, it finishes that completion on the same loop; ordinary accepted deferral is preserved. Tests check success, exact original failure and payload release through both muxers.

Review round record (reviewed head `8eabea8649ac5bc301615dbea6a5f06ac8c477bc`):
'''
body+=f'\n- [Adversarial review]({blob}F71b2-R71adv-final.md) ([full findings]({blob}F71b2-R71adv-findings.md)): completed-close finding addressed by [the close fix]({base}/commit/ecafb3ab4b321b1b24be6c26b0c39356865f87e0) and [reentrant ordering guard]({base}/commit/6bdb29896ff411d1beca2c434e77b742223e557a). Its two documentation findings are answered by [the coordinate correction]({base}/commit/{doc}) and this description.\n'
body+=f'- [Coverage review]({blob}F71b2-R71tc-final.md) ([full findings]({blob}F71b2-R71tc-findings.md)): [four pending failure/cancellation cases]({base}/commit/{coverage}) now fail under the exact success-only listener mutation, then pass with the original converter restored. They check terminal state and original cause after real executor barriers.\n'
body+=f'- This resumed round: [stream-write sweep fix and six guards]({base}/commit/d6eca64f92fc1d1cc4d44b39a188afc98ae0abec), plus registration-race, queued shutdown, nullable generic success and caller-ownership cases. Separate local test-comprehensiveness and code-review passes are recorded in [the findings]({blob}F71b2-findings.md); no new external review approval is claimed.\n'
body+='''
Local controls:

- Close regression: original assertion-red 9608 ms; fixed 8359 ms; fix reversal red 9865 ms. This resumed lane also confirmed the original pushed head: close 1/1 (9522 ms), converters 6/6 (2880 ms).
'''
for label in ['stream-red','stream-green','pending-mutation','pending-green','state-mutation','state-green']:
 p=out/f'F71b2-{label}.json'
 if p.exists():
  r=json.loads(p.read_text()); total=int(r.get('tests',0)); failed=int(r.get('failures',0))+int(r.get('errors',0)); ms=round(float(r.get('time',0))*1000)
  body+=f'- `{label}`: {total-failed}/{total} pass, {failed} assertion/error results, {ms} ms. [XML]({blob}F71b2-{label}.xml), [full log]({blob}F71b2-{label}.log).\n'
body+='\nFirst runtime attempt per class at final source head `'+head+'` (one class per bounded Gradle command; XML counts):\n\n| Class | Pass / total | Fail / skip | XML ms |\n|---|---:|---:|---:|\n'
records=sorted(out.glob('F71b2-final-*.json'))
for p in records:
 r=json.loads(p.read_text());total=int(r.get('tests',0));fails=int(r.get('failures',0))+int(r.get('errors',0));skip=int(r.get('skipped',0));ms=round(float(r.get('time',0))*1000)
 body+=f'| {r["class"].rsplit(".",1)[1]} | {total-fails-skip}/{total} | {fails}/{skip} | {ms} |\n'
body+='''
Both initial close and converter commands hit their unchanged 600-second outer limits during test-source compilation, before any test body or XML. Those first-command failures are retained, including their full daemon traces; they are not counted as passing runtime attempts. A separate bounded offline `compileTestKotlin` then succeeded in 7m14s. No failed test was rerun unchanged into green; the deliberately failing mutation/control runs above are recorded separately from restored-head validation.

'''
body+=f'[All command, XML and full failure records]({evidence}). [Initial close daemon trace]({blob}F71b2-initial-close-daemon.log); [initial converter daemon trace]({blob}F71b2-initial-converter-daemon.log).\n\n'
body+='''GitHub Actions does not run tests for this fork: enterprise policy blocks its external actions, so the verification evidence is local. No workflow was rerun, requested or watched.

No existing test files were edited: all test-file changes relative to `develop` are additions. No test timeout or iteration count was changed, and no test or assertion was weakened, skipped or deleted. No reflection or mocks were added.

`build.gradle.kts` assigns `community.kotlin.libp2p:jvm-libp2p:1.3.0-codexcoder21-snapshot-41`; `FORK.md` now agrees and keeps assignment separate from publication. This lane did not merge, enqueue, publish or deploy.

🤖 Generated with [Claude Code](https://claude.com/claude-code)'''
(out/'F71b2-description.md').write_text(body)
(out/'F71b2-body.json').write_text(json.dumps({'body':body}))
print('Prepared description',len(body),'characters;',len(records),'final class records')
