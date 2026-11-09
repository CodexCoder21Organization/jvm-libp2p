from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

root=Path.cwd()
evidence=root/'.lane-evidence'
script=root/'.github/scripts/download-verified.sh'
selector='DownloadVerifiedTest.test_504_retries_wait_one_then_two_seconds'
original=script.read_text()

def run(name, expected_passed, expected_total, selected=True):
    command=['python3','.github/scripts/test_download_verified.py','--log',f'.lane-evidence/{name}.xml']
    if selected:
        command.append(selector)
    with (evidence/f'{name}.log').open('w') as log:
        result=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,timeout=180 if not selected else 45)
    report=ET.parse(evidence/f'{name}.xml').getroot()
    total=int(report.get('tests'))
    passed=total-int(report.get('failures'))-int(report.get('errors'))
    print(f'{name}: FIRST {passed}/{total}; exit {result.returncode}',flush=True)
    print((evidence/f'{name}.log').read_text(),flush=True)
    assert (passed,total)==(expected_passed,expected_total)
    assert result.returncode==(0 if passed==total else 1)

run('pacing-green',1,1)
assert original.count('      sleep "$retry_delay"\n')==1
try:
    script.write_text(original.replace('      sleep "$retry_delay"\n','      : # Backoff omitted for reversal verification.\n'))
    run('pacing-reversal',0,1)
finally:
    script.write_text(original)
run('pacing-restored',1,1)
run('download-register',17,17,selected=False)
