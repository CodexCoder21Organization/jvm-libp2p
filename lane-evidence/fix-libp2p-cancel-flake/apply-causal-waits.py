from pathlib import Path
base=Path('libp2p/src/test/kotlin/io/libp2p/network')
paths=[base/'NetworkProvisionalCancellationBeforePublicationTest.kt',base/'NetworkProvisionalMplexCancellationBeforePublicationTest.kt']+list(base.glob('*Cancellation*EvidenceTest.kt'))
for p in paths:
 s=p.read_text()
 assert 'val responderStarted = ' not in s or 'OrderingEvidenceTest' in p.name
 if 'val responderStarted = ' not in s:
  s=s.replace('        val responderStarts = AtomicInteger()', '        val responderStarts = AtomicInteger()\n        val responderStarted = CompletableFuture<Unit>()')
  s=s.replace('                    responderStarts.incrementAndGet()', '                    responderStarts.incrementAndGet()\n                    responderStarted.complete(Unit)')
 if 'OrderingEvidenceTest' in p.name:
  s=s.replace('            responderEntered.get(5, SECONDS)', '            responderEntered.get(5, SECONDS)\n            releaseResponder.countDown()\n            responderStarted.get(5, SECONDS)')
 else:
  s=s.replace('            later.controller.get(5, SECONDS)', '            later.controller.get(5, SECONDS)\n            // Initiator readiness does not order the peer responder callback.\n            responderStarted.get(5, SECONDS)')
 p.write_text(s)
p=base/'NetworkProvisionalCancellationSiblingTest.kt'
s=p.read_text().replace('import io.libp2p.protocol.ProtocolHandler','import io.libp2p.protocol.ProtocolMessageHandler\nimport io.netty.buffer.ByteBuf\nimport io.netty.buffer.Unpooled\nimport io.libp2p.protocol.ProtocolHandler')
s=s.replace('                override fun onStartInitiator(stream: Stream) = CompletableFuture.completedFuture(Unit)', '''                override fun onStartInitiator(stream: Stream): CompletableFuture<Unit> {
                    val started = CompletableFuture<Unit>()
                    stream.pushHandler(object : ProtocolMessageHandler<ByteBuf> {
                        override fun onMessage(stream: Stream, msg: ByteBuf) {
                            assertEquals(1, msg.readableBytes(), "The responder start acknowledgment must contain one byte")
                            assertEquals(1, msg.readUnsignedByte().toInt(), "The responder must acknowledge its start")
                            started.complete(Unit)
                        }
                        override fun onClosed(stream: Stream) {
                            started.completeExceptionally(ConnectionClosedException("The child closed before its responder start acknowledgment arrived"))
                        }
                        override fun onException(cause: Throwable?) {
                            started.completeExceptionally(cause ?: IllegalStateException("The responder start acknowledgment failed without a cause"))
                        }
                    })
                    return started
                }''')
s=s.replace('                    return CompletableFuture.completedFuture(Unit)', '                    return stream.writeAndFlushWithFuture(Unpooled.buffer(1).writeByte(1))')
p.write_text(s)
