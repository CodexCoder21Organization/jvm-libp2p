from pathlib import Path
import subprocess
base=Path('libp2p/src/test/kotlin/io/libp2p/network')
p=base/'NetworkProvisionalCancellationSiblingTest.kt'
s=subprocess.check_output(['git','show','a926ff23:libp2p/src/test/kotlin/io/libp2p/network/NetworkProvisionalCancellationSiblingTest.kt'],text=True)
s=s.replace('        val responders = AtomicInteger()', '        val responders = AtomicInteger()\n        val responderStarted = CompletableFuture<Unit>()')
s=s.replace('                    responders.incrementAndGet()', '                    responders.incrementAndGet()\n                    responderStarted.complete(Unit)')
s=s.replace('            val later = client.newStream<Unit>', '            assertEquals(0, responders.get(), "The cancelled queued child must not start a responder before the later child opens")\n            val later = client.newStream<Unit>')
s=s.replace('            later.controller.get(5, SECONDS)', '            later.controller.get(5, SECONDS)\n            // Initiator readiness does not order the peer responder callback.\n            responderStarted.get(5, SECONDS)')
p.write_text(s)
p=base/'NetworkProvisionalCancellationSiblingOrderingEvidenceTest.kt'
s=Path('.lane-scratch/NetworkProvisionalCancellationSiblingOrderingEvidenceTest.kt').read_text()
s=s.replace('            responderEntered.get(5, SECONDS)', '            responderEntered.get(5, SECONDS)\n            releaseResponder.countDown()\n            responderStarted.get(5, SECONDS)')
p.write_text(s)
