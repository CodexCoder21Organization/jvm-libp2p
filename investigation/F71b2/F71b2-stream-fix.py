from pathlib import Path
repo=Path(__file__).resolve().parent.parent/'ws/F71b2/jvm-libp2p'
p=repo/'libp2p/src/main/kotlin/io/libp2p/transport/implementation/StreamOverNetty.kt'
s=p.read_text().replace('import io.libp2p.etc.types.toVoidCompletableFuture','import io.libp2p.etc.types.toCompletableFuture\nimport io.libp2p.etc.types.toVoidCompletableFuture')
s=s.replace('nettyChannel.writeAndFlush(msg).addListener {\n            if (it.isSuccess)', 'nettyChannel.writeAndFlush(msg).toCompletableFuture().whenComplete { _, failure ->\n            if (failure == null)')
s=s.replace('?: it.cause()\n                        ?: ConnectionClosedException("Stream write failed without a reported cause: $nettyChannel")', '?: failure')
s=s.replace('import io.libp2p.core.ConnectionClosedException\n','')
p.write_text(s)
p=repo/'libp2p/src/main/kotlin/io/libp2p/etc/util/netty/mux/MuxChannel.kt'
s=p.read_text().replace('import java.net.SocketAddress','import java.net.SocketAddress\nimport java.util.concurrent.RejectedExecutionException')
a='''                    parentFuture.addListener {
                        eventLoop().execute {
                            waitingForParentWrite = false
                            removeParentWrite(buf, parentFuture.causeOrNull())
                            doWrite(buf)
                        }
                    }'''
b='''                    parentFuture.addListener {
                        val completeWrite = Runnable {
                            waitingForParentWrite = false
                            removeParentWrite(buf, parentFuture.causeOrNull())
                            doWrite(buf)
                        }
                        try {
                            eventLoop().execute(completeWrite)
                        } catch (rejected: RejectedExecutionException) {
                            // The parent listener already owns the child event loop. A full queue
                            // must not discard its completed write or retain the child payload.
                            if (!eventLoop().inEventLoop()) throw rejected
                            completeWrite.run()
                        }
                    }'''
assert a in s
p.write_text(s.replace(a,b))
