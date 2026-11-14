from pathlib import Path
import hashlib
import json
import subprocess

evidence = Path(__file__).resolve().parent
lane = evidence.parents[2]
repo = lane / 'jvm-libp2p'
paths = subprocess.check_output(['git', 'diff', '--name-only', 'origin/develop', 'HEAD', '--', '*Test*'], cwd=repo, text=True).splitlines()
ledger = []
for name in paths:
    source = (repo / name).read_bytes()
    base = subprocess.run(['git', 'show', 'origin/develop:' + name], cwd=repo, capture_output=True)
    lines = source.decode().splitlines()
    ledger.append(dict(path=name, sha256=hashlib.sha256(source).hexdigest(), lines=len(lines),
                       present_on_base=base.returncode == 0,
                       deadline_lines=[i for i,l in enumerate(lines,1) if 'cleanupDeadline' in l],
                       timed_wait_lines=[i for i,l in enumerate(lines,1) if '.get(' in l and 'TimeUnit.' in l]))
result = dict(touched=len(ledger), existing=sum(x['present_on_base'] for x in ledger),
              new=sum(not x['present_on_base'] for x in ledger), files=ledger)
(evidence / 'cleanup-envelope-audit.json').write_text(json.dumps(result, indent=2))
native = lane / 'netty-quic-classes-source/io/netty/handler/codec/quic/BoringSSLCertificateCallback.java'
source = native.read_text()
logs = {}
for name in ['g4-red', 'g4-green']:
    text = (lane / (name + '.log')).read_text()
    logs[name] = dict(certificate_callback_errors=text.count('CERT_CB_ERROR'),
                      connection_setup_errors=text.count('An error during handshake'))
facts = dict(native_artifact='io.netty:netty-codec-classes-quic:4.2.10.Final',
             source_path='io/netty/handler/codec/quic/BoringSSLCertificateCallback.java',
             source_sha256=hashlib.sha256(native.read_bytes()).hexdigest(),
             has_ed25519_mapping=any(x in source for x in ['Ed25519', 'ED25519', 'EdDSA']),
             certificate_mapping_lines=[i for i,l in enumerate(source.splitlines(),1) if 'keyType(' in l or 'chooseEngineServerAlias' in l or 'chooseEngineClientAlias' in l], logs=logs)
(evidence / 'g4-setup-failure-facts.json').write_text(json.dumps(facts, indent=2))
print(json.dumps(dict(touched=result['touched'], existing=result['existing'], new=result['new'],
                     native_setup=facts)))
