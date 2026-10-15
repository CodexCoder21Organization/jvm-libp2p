import os, subprocess, sys, signal, time, json
seconds=int(sys.argv[1]); log=sys.argv[2]; command=sys.argv[3:]
start=time.time()
with open(log,'w') as f:
    f.write('COMMAND '+repr(command)+'\n'); f.flush()
    p=subprocess.Popen(command,stdout=f,stderr=subprocess.STDOUT,start_new_session=True)
    print('pid',p.pid,'log',log,flush=True)
    try: code=p.wait(timeout=seconds)
    except subprocess.TimeoutExpired:
        os.killpg(p.pid,signal.SIGTERM)
        try: p.wait(timeout=10)
        except subprocess.TimeoutExpired: os.killpg(p.pid,signal.SIGKILL); p.wait()
        code=124
    finally:
        try: os.killpg(p.pid,signal.SIGTERM)
        except ProcessLookupError: pass
    f.write('\nRESULT '+json.dumps({'exit':code,'elapsed':time.time()-start})+'\n')
print('exit',code,'elapsed',time.time()-start,flush=True)
sys.exit(code)
