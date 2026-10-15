import runpy,subprocess
from pathlib import Path
values=runpy.run_path('../evidence/lane241-evidence/r241-make-trace.py')
s=values['s']
second=s.index('\n    @ParameterizedTest',s.index('\n    @ParameterizedTest')+1)
s=s[:second]+'\n}\n'
s=s.replace('ChildReadPauseTcpTest','ChildReadPauseCoalescedRecordTest').replace('PAUSE_PROTOCOL','COALESCED_PAUSE_PROTOCOL')
s=s.replace('        val traceEvents =', '        val writeBatches = java.util.concurrent.ConcurrentHashMap<io.libp2p.core.P2PChannel, ForcedPlaintextWriteBatch>()\n        val traceEvents =',1)
s=s.replace('            secureChannels { add(::PlaintextInsecureChannel) }','''            secureChannels {
                add { key, _ ->
                    val plaintext = PlaintextInsecureChannel(key)
                    if (!listen) plaintext else object : io.libp2p.core.security.SecureChannel by plaintext {
                        override fun initChannel(ch: io.libp2p.core.P2PChannel, selectedProtocol: String): CompletableFuture<out io.libp2p.core.security.SecureChannel.Session> {
                            val batch = writeBatches[ch] ?: error("Missing per-connection plaintext write batch")
                            ch.pushHandler(object : io.netty.channel.ChannelDuplexHandler() {
                                override fun channelActive(ctx: ChannelHandlerContext) {
                                    ctx.fireChannelActive()
                                    batch.releaseBatch()
                                }
                            })
                            return plaintext.initChannel(ch, selectedProtocol)
                        }
                    }
                }
            }''',1)
s=s.replace('                beforeSecureHandler.addHandler { it.pushHandler(recorder("raw")) }','''                beforeSecureHandler.addHandler { it.pushHandler(recorder("raw")) }
                if (listen) beforeSecureHandler.addHandler {
                    val batch = ForcedPlaintextWriteBatch(true)
                    writeBatches[it] = batch
                    it.pushHandler(batch)
                }''',1)
probe=Path('../evidence/lane241-evidence/r220-coalescing-test.kt').read_text()
batch=probe[probe.index('private class HandshakeWriteBatch'):].replace('HandshakeWriteBatch','ForcedPlaintextWriteBatch').replace('private lateinit var context: ChannelHandlerContext','private lateinit var context: ChannelHandlerContext').replace('ChannelDuplexHandler','io.netty.channel.ChannelDuplexHandler').replace('ChannelPromise','io.netty.channel.ChannelPromise').replace('ConnectionClosedException()','io.libp2p.core.ConnectionClosedException()')
s+='\n'+batch
Path('../evidence/lane241-evidence/ChildReadPauseCoalescedRecordTest.kt').write_text(s)
