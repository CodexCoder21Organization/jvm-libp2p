from pathlib import Path
base=Path('libp2p/src/test/kotlin/io/libp2p/network')
paths=[base/'NetworkProvisionalCancellationBeforePublicationTest.kt',base/'NetworkProvisionalMplexCancellationBeforePublicationTest.kt']+[p for p in base.glob('*Cancellation*EvidenceTest.kt') if 'SiblingOrdering' not in p.name]
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
