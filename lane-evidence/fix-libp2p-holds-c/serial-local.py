from pathlib import Path
import subprocess,sys,os
root=Path('/code/workspace/fix-libp2p-holds-c');cp=(root/'scratch/consumer-classpath.txt').read_text()
if not (root/'scratch/SerialLocal.class').exists():
 result=subprocess.run(['javac','-proc:none','-cp',cp,str(root/'scratch/SerialLocal.java')])
 if result.returncode:raise SystemExit(result.returncode)
args=['java','-Xmx768m','-cp',str(root/'scratch')+':'+cp,'SerialLocal',str(root/'UrlResolver'),str(root/'scratch/consumer-cache')]+sys.argv[1:]
os.execvp(args[0],args)
