from pathlib import Path
import subprocess,json,time,sys,xml.etree.ElementTree as ET,hashlib
lane=Path(__file__).resolve().parents[3]
repo=lane/'jvm-libp2p'; build=lane/'r93a-build'; evidence=Path(__file__).resolve().parent
slot=str(evidence/'with-slot.sh'); java='/opt/java/bin/java'
cp=(build/'classpath').read_text(); compiler=(build/'compiler-classpath').read_text()
base=[java,'-cp',compiler,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','11','-language-version','1.6','-Xjvm-default=all','-classpath',cp]
def command(name,args,expected=0):
    start=time.time()
    with (evidence/(name+'.log')).open('w') as log:
        p=subprocess.Popen([slot,'timeout','600']+args,stdout=log,stderr=subprocess.STDOUT,cwd=repo)
        (build/'active-pid').write_text(str(p.pid))
        try: code=p.wait(timeout=4210)
        except subprocess.TimeoutExpired:
            p.terminate(); p.wait(timeout=30); raise
        finally: (build/'active-pid').unlink(missing_ok=True)
    (evidence/(name+'.exit')).write_text(str(code)+'\n')
    print(json.dumps({'stage':name,'exit':code,'seconds':round(time.time()-start,2)}),flush=True)
    if expected is not None and code!=expected: sys.exit(code or 1)
    return code
sources=sorted((repo/'libp2p/src/test/kotlin/io/libp2p/transport').glob('*Failed*Test.kt'))
sources=[x for x in sources if x.name in ['PlainNettyTransportFailedCloseSetTest.kt','PlainNettyTransportFailedCloseTest.kt','QuicTransportFailedCloseTest.kt','QuicTransportFailedConnectionSetTest.kt','QuicTransportFailedDatagramCloseTest.kt']]
sources.append(repo/'libp2p/src/test/kotlin/io/libp2p/transport/QuicTransportClosedChildParentTest.kt')
command('compile-tests',base+['-d',str(build/'test-classes')]+list(map(str,sources)))
command('compile-mutant',base+['-d',str(build/'mutant-classes'),str(build/'mutant/QuicTransport.kt'),str(repo/'libp2p/src/main/kotlin/io/libp2p/transport/implementation/NettyChannelClose.kt')])
console='/tmp/flibp6-junit-console.jar'
for mode in ('mutant','green'):
    runtime=(str(build/'mutant-classes')+':' if mode=='mutant' else '')+str(build/'test-classes')+':'+cp
    selected=['--select-class','io.libp2p.transport.QuicTransportClosedChildParentTest']
    if mode=='green':
        selected=[]
        for src in sources:selected+=['--select-class','io.libp2p.transport.'+src.stem]
    code=command(mode,[java,'-jar',console,'execute','--class-path',runtime]+selected+['--reports-dir',str(evidence/mode),'--fail-if-no-tests'],None)
    counts={'tests':0,'failures':0,'errors':0,'skips':0,'retry_tags':[],'failure_cases':[]}
    for xml in (evidence/mode).glob('*.xml'):
        tree=ET.parse(xml);root=tree.getroot()
        for prop in root.findall('.//properties'):
            for parent in root.iter():
                if prop in list(parent):parent.remove(prop);break
        for c in root.iter('testcase'):
            counts['tests']+=1
            if c.find('failure') is not None:counts['failures']+=1;counts['failure_cases'].append({'class':c.get('classname'),'name':c.get('name'),'message':c.find('failure').get('message')})
            if c.find('error') is not None:counts['errors']+=1
            if c.find('skipped') is not None:counts['skips']+=1
        tree.write(xml,encoding='unicode')
    counts['passes']=counts['tests']-counts['failures']-counts['errors']-counts['skips'];counts['exit']=code
    (evidence/(mode+'-summary.json')).write_text(json.dumps(counts,indent=2))
    print(json.dumps({'mode':mode,'counts':{k:v for k,v in counts.items() if k!='failure_cases'}}),flush=True)
