import hashlib, json, pathlib, re, subprocess
root=pathlib.Path.cwd(); repo=root/'workspace/jvm-libp2p'; base='origin/develop'; head='HEAD'
def git(*a): return subprocess.check_output(['git','-C',str(repo),*a],text=True,stderr=subprocess.DEVNULL).strip()
def maybe_git(*a):
    try:return git(*a)
    except subprocess.CalledProcessError:return None
def sites(lines,pattern,kind_group=1):
    out=[]
    for n,s in enumerate(lines,1):
        m=re.search(pattern,s)
        if m: out.append({'line':n,'kind':m.group(kind_group) if kind_group else m.group(0)})
    return out
def block_end(lines, ix):
    depth=0; opened=False
    for k in range(ix,len(lines)):
        s=lines[k]
        # These test bodies use ordinary blocks; string braces are limited to messages/interpolations.
        depth+=s.count('{')-s.count('}')
        opened=opened or ('{' in s)
        if opened and depth<=0:return k+1
    return len(lines)
def test_entries(lines):
    out=[]
    for ix,s in enumerate(lines):
        m=re.match(r'    @(Test|ParameterizedTest)\b',s)
        if not m: continue
        j=ix+1; params=[]
        while j<len(lines) and j<ix+5 and not re.search(r'\bfun\s+\w+\s*\(',lines[j]):
            vm=re.search(r'@ValueSource\((\w+)\s*=\s*\[([^]]*)\]',lines[j])
            if vm:
                vals=re.findall(r'"(?:\\.|[^"\\])*"|\b(?:true|false|\d+)\b',vm.group(2))
                params.append({'kind':vm.group(1),'count':len(vals)})
            j+=1
        if j>=len(lines): continue
        name=re.search(r'fun\s+(\w+)',lines[j]).group(1)
        end=block_end(lines,j); body=lines[j:end]
        asserts=sites(body,r'\b(assert\w+|fail|check)\s*\(',1)
        waits=[]
        for n,line in enumerate(body,j+1):
            for m2 in re.finditer(r'\.(await|get|join)\s*\(',line):
                tail=line[m2.end():]; budget=re.search(r'(\d+)\s*,\s*TimeUnit\.(\w+)',tail)
                waits.append({'line':n,'kind':m2.group(1),'budget':(budget.group(1)+' '+budget.group(2)) if budget else None})
        loops=[]
        for n,line in enumerate(body,j+1):
            m2=re.search(r'\brepeat\s*\(\s*([^)]*)',line)
            if m2: loops.append({'line':n,'kind':'repeat','count':m2.group(1).strip()})
            elif re.search(r'\bfor\s*\(',line):
                val=re.search(r'\bin\s+([^)]*)',line); loops.append({'line':n,'kind':'for','range':val.group(1).strip() if val else None})
            elif re.search(r'\bwhile\s*\(',line): loops.append({'line':n,'kind':'while','bound':'condition-driven'})
        clean=[{'line':n,'kind':mm.group(1)} for n,line in enumerate(body,j+1) if (mm:=re.search(r'\b(finally|close|stop|shutdownGracefully|shutdown|release|dispose|cancel)\b',line))]
        out.append({'name':name,'annotation':m.group(1),'start_line':j+1,'end_line':end,'parameter_sources':params,'invocations':sum(x['count'] for x in params) if params else 1,
                    'assertions':asserts,'gates':[n for n,line in enumerate(body,j+1) if re.search(r'\b(?:CountDownLatch|CyclicBarrier|Semaphore|CompletableFuture|Phaser)\b',line)],'waits':waits,'iterations':loops,'cleanup_sites':clean})
    return out
paths=subprocess.check_output(['git','-C',str(repo),'diff','--name-only',base,head,'--','*Test.kt'],text=True).splitlines()
numstat=subprocess.check_output(['git','-C',str(repo),'diff','--numstat',base,head,'--','*Test.kt'],text=True).splitlines()
stat={}
for row in numstat:
    a,d,p=row.split('\t');stat[p]={'added':int(a),'deleted':int(d)}
ledger={'repository':'CodexCoder21Organization/jvm-libp2p','base_ref':base,'base_commit':git('rev-parse',base),'head_ref':head,'head_commit':git('rev-parse',head),'merge_base':git('merge-base',head,base),'scope':'git diff origin/develop HEAD -- *Test.kt','test_file_count':len(paths),'files':[]}
for rel in paths:
    p=repo/rel; data=p.read_bytes(); lines=data.decode().splitlines()
    entry={'path':rel,'line_count':len(lines),'sha256':hashlib.sha256(data).hexdigest(),'head_blob':maybe_git('rev-parse',f'HEAD:{rel}'),'base_blob':maybe_git('rev-parse',f'{base}:{rel}'),'diff_lines':stat[rel],
       'tests':test_entries(lines),'assertions':sites(lines,r'\b(assert\w+|fail|check)\s*\(',1),
       'gates':sites(lines,r'\b(CountDownLatch|CyclicBarrier|Semaphore|CompletableFuture|Phaser)\b',1),
       'waits':sites(lines,r'\.(await|get|join)\s*\(',1),
       'executor_dispatch':sites(lines,r'\.(submit|execute)\s*[<(]',1),
       'iteration_sites':sites(lines,r'\b(repeat|for|while)\s*[<(]',1),
       'cleanup_sites':[{'line':n,'kind':m.group(1)} for n,line in enumerate(lines,1) if (m:=re.search(r'\b(finally|close|stop|shutdownGracefully|shutdown|release|dispose|cancel)\b',line))],
       'printed_diagnostics':sites(lines,r'\b(?:println|printStackTrace|System\.out|System\.err)\b',0),
       'logger_sites':sites(lines,r'\b(?:logger|log)\.(?:trace|debug|info|warn|error)\s*\(',0)}
    ledger['files'].append(entry)
(root/'test-inventory/ledger.json').write_text(json.dumps(ledger,indent=2)+'\n')
print(f"files={len(paths)} test_functions={sum(len(f['tests']) for f in ledger['files'])} assertions={sum(len(f['assertions']) for f in ledger['files'])} waits={sum(len(f['waits']) for f in ledger['files'])}")
for f in ledger['files']:
 print(f"{f['path'].split('/')[-1]}\t{f['line_count']} lines\t{len(f['tests'])} tests\tassert={len(f['assertions'])}\twait={len(f['waits'])}\tloop={len(f['iteration_sites'])}\tprint={len(f['printed_diagnostics'])}")
