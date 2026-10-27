import hashlib,json,os,subprocess,sys,xml.etree.ElementTree as E
from pathlib import Path
case=sys.argv[1]
assert case in ('normal','held-loop','diagnostic','handler')
repo=Path.cwd();out=repo/'guard-evidence'/case;out.mkdir(parents=True,exist_ok=True)
(out/'startup.json').write_text(json.dumps({'case':case,'checkout':str(repo)}))
subprocess.run(['git','config','--global','--add','safe.directory',str(repo)],check=True)
subprocess.run(['git','config','user.name','hq071 verification'],check=True)
subprocess.run(['git','config','user.email','hq071@example.invalid'],check=True)
path=repo/'tests/stressTestRejectedDrainConcurrentHostStops.kts'
normal=['testRejectedDrainLoggingDoesNotHoldOutboxMonitor','testHostStopObservesCompletedCloseWithFullEventLoop','stressTestRejectedDrainConcurrentHostStops','testRawProviderGossipFlushReportsAcceptedWriteFailure']
messages={'held-loop':'hq071 controlled held-loop callback failure','diagnostic':'hq071 controlled diagnostic callback failure','handler':'hq071 controlled request handler failure'}
results=[]
for number in range(1,2 if case=='normal' else 4):
 subprocess.run(['git','fetch','origin','main'],check=True)
 subprocess.run(['git','rebase','origin/main'],check=True)
 original=path.read_text();source=original
 if case!='normal':
  source=source.replace('    val nativeErr = System.err','    val controlEntered = AtomicBoolean()\n    val nativeErr = System.err',1)
  before='rejectedDrainHostStopCycle(id, nativeErr, sinks, awaitRound)';assert source.count(before)==1
  source=source.replace(before,'rejectedDrainHostStopCycle(id, nativeErr, sinks, awaitRound, controlEntered)')
  before='    awaitFaultPhase: () -> Unit,\n)';assert source.count(before)==1
  source=source.replace(before,'    awaitFaultPhase: () -> Unit,\n    controlEntered: AtomicBoolean,\n)')
  before='    assertEquals(0, failures.size,';assert source.count(before)==1
  source=source.replace(before,'    assertTrue(controlEntered.get(), "The callback control must execute its explicit fault")\n'+before)
  if case=='held-loop':
   before='assertTrue(releaseLoop.await(10, TimeUnit.SECONDS), "The test must release the root loop")'
   after=before+'\n            controlEntered.set(true)\n            error("'+messages[case]+'")'
  elif case=='diagnostic':
   before='assertTrue(releaseSink.await(10, TimeUnit.SECONDS), "The test must release the diagnostic sink")'
   after=before+'\n                if (id == 0) { controlEntered.set(true); throw AssertionError("'+messages[case]+'") }'
  else:
   before='            return Libp2pRpcProtocol.RpcResponse.success('
   after='            if (id == 0 && request.id == "first") { controlEntered.set(true); throw AssertionError("'+messages[case]+'") }\n'+before
  assert source.count(before)==1
  source=source.replace(before,after)
 (out/f'run-{number}-input.kts.txt').write_text(source)
 record={'number':number,'head':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),'originalSha256':hashlib.sha256(original.encode()).hexdigest(),'inputSha256':hashlib.sha256(source.encode()).hexdigest()}
 path.write_text(source)
 xml=out/f'run-{number}.xml';log=out/f'run-{number}.log'
 env=os.environ.copy();env['JAVA_TOOL_OPTIONS']=(env.get('JAVA_TOOL_OPTIONS','')+' -Dio.netty.eventLoopThreads=4').strip()
 command=['scripts/test.bash','--local']
 for name in normal if case=='normal' else ['stressTestRejectedDrainConcurrentHostStops']:command+=['--test','tests/'+name+'.kts']
 command+=['--log',str(xml)]
 try:
  with log.open('w') as f:record['exit']=subprocess.run(command,env=env,stdout=f,stderr=subprocess.STDOUT).returncode
 finally:path.write_text(original)
 if not xml.exists():
  record['classification']='PRE_BODY_FAILURE';results.append(record);(out/'summary.json').write_text(json.dumps(results,indent=2));raise RuntimeError('No native XML was produced; inspect the complete command log')
 root=E.parse(xml).getroot();tests=list(root.iter('test'));hist={(h.get('file'),h.get('name')):h for h in root.iter('attempt-history')};first=[]
 for test in tests:
  h=hist.get((test.get('file'),test.get('name')));a=next(iter(h),None) if h is not None else test
  assert a is not None
  first.append({'name':test.get('name'),'success':a.get('success'),'trace':a.findtext('stacktrace') or '', 'console':a.findtext('console-output') or ''})
 record['tests']=len(tests);record['firstPass']=sum(t['success']=='true' for t in first);record['firstFail']=len(first)-record['firstPass'];record['finalPass']=sum(t.get('success')=='true' for t in tests)
 if case=='normal':
  record['classification']='PASS' if len(tests)==4 and record['firstPass']==4 and record['exit']==0 else 'UNEXPECTED_FAILURE'
 else:
  assert len(tests)==1
  if record['firstPass']==1 and record['finalPass']==1 and record['exit']==0:record['classification']='FALSE_PASS'
  elif record['firstFail']==1 and record['finalPass']==0 and messages[case] in first[0]['trace']+first[0]['console']:record['classification']='OBSERVED'
  else:record['classification']='UNEXPECTED_FAILURE'
 results.append(record);(out/'summary.json').write_text(json.dumps(results,indent=2));print(json.dumps({k:v for k,v in record.items() if k!='inputSha256'}),flush=True)
 if record['classification'] in ('UNEXPECTED_FAILURE','PRE_BODY_FAILURE'):raise RuntimeError('Unexpected first-attempt result; stop and diagnose without rerunning')
subprocess.run(['git','bundle','create',str(out/'tested-source.bundle'),'HEAD','^origin/main'],check=True)
if any(r['classification']=='FALSE_PASS' for r in results):raise AssertionError('The explicit callback fault was hidden by the guard; native evidence is retained')
