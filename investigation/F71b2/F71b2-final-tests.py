from pathlib import Path
import subprocess,json,time,sys
out=Path(__file__).resolve().parent
classes=[
'io.libp2p.security.noise.NoisePreparationLifecycleTest',
'io.libp2p.security.noise.NoisePreparationConcurrencyTest',
'io.libp2p.security.noise.NoisePreparationFailureTest',
'io.libp2p.security.noise.NoisePreparationTrafficTest',
'io.libp2p.security.noise.NoiseBindingReadinessTest',
'io.libp2p.security.noise.NoiseHandshakeTest',
'io.libp2p.network.NetworkPendingConnectTest',
'io.libp2p.etc.types.CompletedNettyFutureConversionTest',
'io.libp2p.transport.CompletedConnectionCloseNotificationTest',
'io.libp2p.transport.ReentrantConnectionCloseNotificationTest',
'io.libp2p.transport.CompletedStreamWriteNotificationTest',
'io.libp2p.etc.types.PendingNettyFutureConversionTest',
'io.libp2p.etc.types.NettyFutureConversionStateTest',
'io.libp2p.mux.BufferedHalfCloseFramesTest',
'io.libp2p.mux.yamux.YamuxPrefixFrameFailureTest'
]
for cls in classes:
 # Do not start a command that could exceed the required final cutoff.
 if time.time()>1792862700: # 2026-10-24 17:25 UTC; each command retains a 600s maximum
  print('STOP: final cutoff reserve reached',flush=True);sys.exit(1)
 label='final-'+cls.rsplit('.',1)[1]
 subprocess.run(['python3',str(out/'F71b2-run.py'),label,cls],check=True)
 r=json.loads((out/f'F71b2-{label}.json').read_text())
 if r['exit'] or int(r.get('failures',0)) or int(r.get('errors',0)) or not r.get('tests'):
  print('STOP: first-attempt failure; inspect the preserved full record',flush=True);sys.exit(1)
