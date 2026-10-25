from pathlib import Path
import subprocess,concurrent.futures,time,json,sys
w=Path(__file__).resolve().parent;e=w/'round8'
def invoke(label,cls,lane):return subprocess.run([sys.executable,str(w/'run-captured.py'),label,'io.libp2p.'+cls,lane]).returncode
def run(job):
 label,cls,lane=job
 if label=='final-ReviewTerminalWriteConversionTest':
  deadline=time.monotonic()+120
  while time.monotonic()<deadline:
   log=e/'noise-final-pair/gradle.log';result=e/'noise-final-pair/result.json'
   if result.exists():break
   if log.exists() and any('ROUND8_TEST_START ' in line and '.twenty sequential Noise handshakes stay inline and within the latency budget()' in line for line in log.read_text().splitlines()):break
   time.sleep(.2)
 return label,invoke(label,cls,lane)
jobs=[('noise-final-pair','security.noise.NoiseHandshakeOffEventLoopTest','final-noise'),('final-ReviewTerminalWriteConversionTest','transport.ReviewTerminalWriteConversionTest','final-terminal')]
classes=['ReentrantChildWriteCloseTest','OffLoopParentWriteNotificationTest','ReviewCompletedShutdownTest','ReviewChildLoopAffinityTest','ZeroLengthUnflushedWriteTerminationTest','UnflushedWriteTerminationTest','QueuedParentCompletionShutdownTest','DrainedWriteTerminationBatchTest','CompletedStreamWriteNotificationTest','CompletedCapacityResetNotificationTest','CapacityResetQueueOccupancyTest']
# Unique output directories prevent overlapping Gradle project locks.
jobs += [('final-'+c,'transport.'+c,'register-'+str(i)) for i,c in enumerate(classes)]
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
 for label,code in pool.map(run,jobs):print('FINAL_REGISTER',label,code,flush=True)
