from pathlib import Path
import subprocess,difflib
name='libp2p/src/test/kotlin/io/libp2p/mux/ChildReadPauseTcpTest.kt'
original=subprocess.check_output(['git','show','origin/develop:'+name],text=True)
s=original.replace('        val expectedMessages =', '        val traceEvents = ConcurrentLinkedQueue<String>()\n        val expectedMessages =',1)
block='''
            debug {
                val role = if (listen) "server" else "client"
                fun recorder(stage: String) = object : io.netty.channel.ChannelDuplexHandler() {
                    var activations = 0
                    fun record(event: String) {
                        traceEvents.add("${System.nanoTime()} $role.$stage ${channelId} $event")
                    }
                    var channelId = "unset"
                    override fun handlerAdded(ctx: ChannelHandlerContext) {
                        channelId = ctx.channel().id().asShortText()
                    }
                    override fun channelActive(ctx: ChannelHandlerContext) {
                        activations++
                        record("ACTIVE $activations" + if (activations > 1) "\\n" + Exception("Repeated activation").stackTraceToString() else "")
                        ctx.fireChannelActive()
                    }
                    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                        record("READ " + if (msg is ByteBuf) io.netty.buffer.ByteBufUtil.hexDump(msg) else msg.toString())
                        ctx.fireChannelRead(msg)
                    }
                    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: io.netty.channel.ChannelPromise) {
                        record("WRITE " + if (msg is ByteBuf) io.netty.buffer.ByteBufUtil.hexDump(msg) else msg.toString())
                        ctx.write(msg, promise)
                    }
                    override fun close(ctx: ChannelHandlerContext, promise: io.netty.channel.ChannelPromise) {
                        record("CLOSE\\n" + Exception("Physical close $role.$stage").stackTraceToString())
                        ctx.close(promise)
                    }
                    @Suppress("OVERRIDE_DEPRECATION")
                    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                        record("EXCEPTION\\n" + cause.stackTraceToString())
                        ctx.fireExceptionCaught(cause)
                    }
                    override fun channelInactive(ctx: ChannelHandlerContext) {
                        record("INACTIVE")
                        ctx.fireChannelInactive()
                    }
                }
                beforeSecureHandler.addHandler { it.pushHandler(recorder("raw")) }
                afterSecureHandler.addHandler { it.pushHandler(recorder("after")) }
                muxFramesHandler.addHandler { it.pushHandler(recorder("frames")) }
            }'''
needle='            protocols { add(binding) }'
s=s.replace(needle,needle+block,1)
needle='                    outboundPayloads.forEach { ReferenceCountUtil.safeRelease(it) }'
s=s.replace(needle,needle+'\n                    println("R241 TRACE $muxer\\n" + traceEvents.joinToString("\\n"))',1)
p=Path('../evidence/lane241-evidence/r241-buffered-trace.patch')
p.write_text(''.join(difflib.unified_diff(original.splitlines(True),s.splitlines(True),fromfile='a/'+name,tofile='b/'+name)))
