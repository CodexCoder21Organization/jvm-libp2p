from pathlib import Path
import subprocess,os,shutil,json,hashlib
r=Path(__file__).resolve().parent;repo=r.parent/'jvm-libp2p';dest=repo/'.review-round8';branch='wip/F68g-20261024'
if dest.exists():shutil.rmtree(dest)
dest.mkdir();shutil.copy2(repo.parent.parent.parent/'out/F68g-findings.md',dest/'findings.md')
allowed={'.log','.xml','.json','.py','.java','.gradle','.diff','.txt'}
skip_dirs={'gradle-home','control-classes','__pycache__','original-production'}
for current,dirs,files in os.walk(r,followlinks=False):
 dirs[:]=[x for x in dirs if x not in skip_dirs]
 for name in files:
  p=Path(current)/name
  if p.suffix not in allowed or p.is_symlink():continue
  if name in {'final-ps.txt','pr-initial.json','pr-before-patch.json','pr-patch-response.json','body.json'}:continue
  if name.startswith('evidence-index') or name.startswith('evidence-push'):continue
  target=dest/'runtime'/p.relative_to(r);target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(p,target)
head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip()
(dest/'README.md').write_text('''# Readiness probe round 8 evidence

PR: https://github.com/CodexCoder21Organization/jvm-libp2p/pull/68
Source head: https://github.com/CodexCoder21Organization/jvm-libp2p/commit/'''+head+'''

This branch stores evidence and diagnostic source, separately from the PR source branch. No compiled package is published. The PR branch changes only three test/support files.

Read findings.md for all first-attempt results, complete failure records and the final brief audit. Native Gradle XML and per-command logs are under runtime/. Every load batch is the round-7 harness: one native Gradle class plus seven independent JUnit Platform Launcher JVMs, each running all three unchanged readiness cases with its own cold subject children. That is eight genuine concurrent class instances and24 cases. No CPU spinner or artificial load was used.

- before-alone / before-load8: original JFR baseline, source32bc3cda.
- after-alone / after-load8: rejected synchronous JVM file-logging candidate73ff3c70; both real first-attempt failures retained,22/24 passed. Never retried unchanged.
- observer-alone / observer-load8: in-memory java.lang.instrument observer4fd50c8e;24/24 first attempt.
- final-alone / final-load8 / final-Algorithm: final90db5389 JAR-resource scope;24/24 load proof and both affected callers checked.
- class-*: remaining native class records. final-register.json maps all eleven classes to their evidence.
- observation-control-*: real class definition before/inside/after the interval, and omitted-agent controls. Inside and omitted-agent are intended runtime assertion failures. Temporary source was restored.
- reverted-*: production-only source reversal. Traffic4/4 and Binding3/3 fail at the intended checks. All production source and test bytecode restored afterward.
- owned-process-audit.json and gradle-stop.log confirm cleanup. The raw system-wide ps output was kept in the requested external output directory; it is omitted here because it also contains unrelated process arguments.

Timings in timings.json are READY/BODY/CLEANUP observations from the unchanged helper. total_before_exit is their sum, matching the round-7 convention; the same helper additionally requires exit within the unchanged30-second aggregate and5-second exit cap. No bound or assertion was weakened.

The original earlier unfinished step remains unknown. The measured JFR start/dump/parse gaps do not dominate BODY; the constructor-done marker is pre-READY and includes earlier fixture work. No production cause is claimed.
''')
index=r/'evidence-index';index.unlink(missing_ok=True);env=dict(os.environ,GIT_INDEX_FILE=str(index))
try:
 subprocess.run(['git','read-tree','HEAD'],cwd=repo,env=env,check=True)
 subprocess.run(['git','add','-f','.review-round8'],cwd=repo,env=env,check=True)
 tree=subprocess.check_output(['git','write-tree'],cwd=repo,env=env,text=True).strip()
 old=subprocess.run(['git','rev-parse','--verify','refs/heads/'+branch],cwd=repo,text=True,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL)
 parent=old.stdout.strip() if old.returncode==0 else head
 commit=subprocess.check_output(['git','commit-tree',tree,'-p',parent],cwd=repo,text=True,input='Record round 8 readiness measurements and controls\n').strip()
 subprocess.run(['git','update-ref','refs/heads/'+branch,commit],cwd=repo,check=True)
 subprocess.run(['git','push','origin',commit+':refs/heads/'+branch],cwd=repo,check=True)
 (r/'evidence-head.txt').write_text(commit+'\n');print('EVIDENCE_HEAD='+commit)
finally:
 shutil.rmtree(dest)
 index.unlink(missing_ok=True)
