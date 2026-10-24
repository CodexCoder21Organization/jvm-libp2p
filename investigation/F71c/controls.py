import subprocess,pathlib,signal,sys
root=pathlib.Path.cwd(); ev=root.parent.parent.parent/'out'/'F71c-evidence'
paths={
'close':pathlib.Path('libp2p/src/main/kotlin/io/libp2p/transport/implementation/NettyChannelClose.kt'),
'mux':pathlib.Path('libp2p/src/main/kotlin/io/libp2p/etc/util/netty/mux/MuxChannel.kt'),
'stream':pathlib.Path('libp2p/src/main/kotlin/io/libp2p/transport/implementation/StreamOverNetty.kt')}
saved={k:p.read_text() for k,p in paths.items()}
def restore():
 for k,p in paths.items():p.write_text(saved[k])
def run(label,cls):
 print('START '+label,flush=True)
 with (ev/(label+'-runner.log')).open('w') as log:r=subprocess.run([sys.executable,str(ev/'run-class.py'),label,cls],stdout=log,stderr=subprocess.STDOUT)
 print('END '+label+' exit='+str(r.returncode),flush=True)
def stop(signum,frame):raise RuntimeError('Controls reached their hard deadline')
signal.signal(signal.SIGTERM,stop)
try:
 scope='(channel.attr(closeNotificationKey).get()?.get() ?: 0) > 0'
 assert scope in saved['close']
 paths['close'].write_text(saved['close'].replace('if ('+scope+')','if (channel.eventLoop().inEventLoop())'))
 run('nested-reversal','io.libp2p.transport.NestedConnectionCloseNotificationTest')
 restore()
 paths['close'].write_text(saved['close'].replace('if ('+scope+')','if (channel.eventLoop().inEventLoop() && '+scope+')'))
 run('off-thread-reversal','io.libp2p.transport.OffThreadConnectionCloseNotificationTest')
 restore()
 paths['mux'].write_bytes(subprocess.check_output(['git','show','2a3b9e2f6e93e8c35e0897f3a6b8e1cc2d2adfc6:'+str(paths['mux'])]))
 run('off-loop-reversal','io.libp2p.transport.OffLoopParentWriteNotificationTest')
 restore()
 old='                    nettyChannel.attr(WRITE_FAILURE).get()'
 assert old in saved['stream']
 paths['stream'].write_text(saved['stream'].replace(old,'''                    if (failure is java.util.concurrent.RejectedExecutionException)
                        java.util.concurrent.RejectedExecutionException("Review replacement lost the original queue rejection")
                    else nettyChannel.attr(WRITE_FAILURE).get()'''))
 run('rejection-message-final-mutation','io.libp2p.transport.CompletedStreamWriteNotificationTest')
 restore()
 new='''                closed.toVoidCompletableFuture().whenComplete { _, failure ->
                    if (failure == null) result.complete(Unit) else result.completeExceptionally(failure)
                }'''
 old='''                closed.addListener {
                    if (it.isSuccess) result.complete(Unit) else result.completeExceptionally(it.cause())
                }'''
 assert new in saved['stream']
 paths['stream'].write_text(saved['stream'].replace(new,old))
 run('capacity-reversal','io.libp2p.transport.CompletedCapacityResetNotificationTest')
finally:restore()
