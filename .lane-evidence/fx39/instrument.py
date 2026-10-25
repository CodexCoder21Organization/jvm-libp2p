from pathlib import Path
import re, sys
root=Path(sys.argv[1]); protocol=Path(sys.argv[2])
def edit(path,old,new,count=1):
 s=path.read_text(); found=s.count(old)
 assert found>=count,(str(path),old,found)
 path.write_text(s.replace(old,new,count))
p=protocol/'src/foundation/url/protocol'
(p/'Fx39Trace.kt').write_text('''package foundation.url.protocol

object Fx39Trace {
    private val events = java.util.concurrent.ConcurrentLinkedQueue<String>()
    fun mark(event: String) {
        val now = System.nanoTime()
        if (events.size < 100000) events.add("$now ${Thread.currentThread().name} $event")
    }
    fun dump(path: String) {
        java.nio.file.Files.write(java.nio.file.Paths.get(path), events.toList())
    }
}
''')
f=p/'RelayService.kt'
edits=[
 ('val totalStart = System.currentTimeMillis()', 'Fx39Trace.mark("sendViaRelay.start relay=$relayPeerId target=$targetPeerId")\n        val totalStart = System.currentTimeMillis()'),
 ('val directDialer = Callable { dial { connectDirectlyToRoutableTarget(targetPeerId, requestDialClock) } }', 'val directDialer = Callable { Fx39Trace.mark("directDial.start target=$targetPeerId"); dial { connectDirectlyToRoutableTarget(targetPeerId, requestDialClock) }.also { Fx39Trace.mark("directDial.end target=$targetPeerId found=${it != null}") } }'),
 ('val relayDialer = Callable {\n            dial {', 'val relayDialer = Callable {\n            Fx39Trace.mark("relayDial.start relay=$relayPeerId")\n            dial {'),
 ('var directTask = CompletionQueueFutureTask', 'Fx39Trace.mark("dialTasks.constructed relay=$relayPeerId")\n        var directTask = CompletionQueueFutureTask'),
 ('raceDialExecutor.execute(directTask)', 'Fx39Trace.mark("dialTasks.enqueue relay=$relayPeerId")\n                    raceDialExecutor.execute(directTask)'),
 ('val connection = directReady ?: relayReady', 'Fx39Trace.mark("dialTasks.finished relay=$relayPeerId")\n        val connection = directReady ?: relayReady'),
 ('connection.sendMessage(json, 0L)', 'Fx39Trace.mark("request.write.start id=${json.optString("requestId")}")\n                    connection.sendMessage(json, 0L)\n                    Fx39Trace.mark("request.write.end id=${json.optString("requestId")}")'),
 ('val message = connection.readMessage(timeout, timeout, timeoutUnit, clock, deadlineMs)', 'Fx39Trace.mark("forwardResponse.read.start")\n    val message = connection.readMessage(timeout, timeout, timeoutUnit, clock, deadlineMs)\n    Fx39Trace.mark("forwardResponse.read.end id=${message?.optString("requestId")}")'),
 ('debugLog { "[RELAY-DEBUG] connectToRelay called,', 'Fx39Trace.mark("connectToRelay.start peer=${peer.peerId}")\n        debugLog { "[RELAY-DEBUG] connectToRelay called,'),
 ('val streamFuture = openRelayDialStream(host, remotePeerId, addr, dialClock)', 'Fx39Trace.mark("connectToRelay.fast.open peer=${peer.peerId}")\n                val streamFuture = openRelayDialStream(host, remotePeerId, addr, dialClock)\n                Fx39Trace.mark("connectToRelay.fast.wait peer=${peer.peerId}")'),
 ('val fastElapsed = System.currentTimeMillis() - fastStartTime', 'Fx39Trace.mark("connectToRelay.fast.ready peer=${peer.peerId}")\n                val fastElapsed = System.currentTimeMillis() - fastStartTime'),
 ('val startTime = System.currentTimeMillis()\n\n        // Exactly one connection survives', 'Fx39Trace.mark("connectToRelay.slow.start peer=${peer.peerId}")\n        val startTime = System.currentTimeMillis()\n\n        // Exactly one connection survives'),
 ('probeReachableAsync(multiaddr, reachabilityTimeoutMs, dialClock).thenCompose { reachable ->', 'probeReachableAsync(multiaddr, reachabilityTimeoutMs, dialClock).thenCompose { reachable ->\n                    Fx39Trace.mark("connectToRelay.probe.end peer=${peer.peerId} reachable=$reachable")'),
 ('streamPromise.controller.thenApply { controller ->', 'streamPromise.controller.thenApply { controller ->\n                                Fx39Trace.mark("connectToRelay.slow.ready peer=${peer.peerId}")'),
 ('val sender = StreamRelayMessageSender(stream)', 'Fx39Trace.mark("relay.incoming.stream peer=${stream.remotePeerId()}")\n        val sender = StreamRelayMessageSender(stream)'),
 ('debugLog { "[RELAY-DEBUG] Received message: ${json.toString().take(200)}" }', 'Fx39Trace.mark("relay.process type=${json.optString("type")} id=${json.optString("requestId")}")\n        debugLog { "[RELAY-DEBUG] Received message: ${json.toString().take(200)}" }'),
 ('debugLog { "[RELAY-DEBUG] handleRelayRequest called,', 'Fx39Trace.mark("relay.handle.request id=${request.requestId}")\n        debugLog { "[RELAY-DEBUG] handleRelayRequest called,'),
 ('registered.controller.sendMessageAwaited(\n                request.withDecrementedHopLimit()', 'Fx39Trace.mark("relay.forward.write.start id=${request.requestId} forward=$forwardRequestId")\n            registered.controller.sendMessageAwaited(\n                request.withDecrementedHopLimit()'),
 ('// Send succeeded — schedule async reply.', 'Fx39Trace.mark("relay.forward.write.end id=${request.requestId} forward=$forwardRequestId")\n        // Send succeeded — schedule async reply.'),
 ('.whenCompleteAsync { targetResponse, throwable ->', '.whenCompleteAsync { targetResponse, throwable ->\n                Fx39Trace.mark("relay.reply.callback id=${request.requestId} forward=$forwardRequestId")'),
 ('internal fun handleRelayResponse(response: RelayProtocol.RelayResponse) {', 'internal fun handleRelayResponse(response: RelayProtocol.RelayResponse) {\n        Fx39Trace.mark("relay.response.received id=${response.requestId}")'),
 ('sourceController.sendAwaitedResponseWithOutbox(\n                    reply.toJson(),', 'sourceController.sendAwaitedResponseWithOutbox(\n                    reply.toJson().also { Fx39Trace.mark("relay.reply.write id=${reply.requestId}") },'),
 ('relayedRequestHandlerExecutor.execute {', 'Fx39Trace.mark("target.handler.enqueue id=${message.requestId}")\n            relayedRequestHandlerExecutor.execute {\n                Fx39Trace.mark("target.handler.worker id=${message.requestId}")'),
 ('val result = handler(message).withoutNotDispatchedMarker()', 'Fx39Trace.mark("target.handler.start id=${message.requestId}")\n                    val result = handler(message).withoutNotDispatchedMarker()\n                    Fx39Trace.mark("target.handler.end id=${message.requestId}")'),
 ('val messageType = json.optString("type", "unknown")\n                        debugLog', 'val messageType = json.optString("type", "unknown")\n                        Fx39Trace.mark("target.listener.received type=$messageType id=${json.optString("requestId")}")\n                        debugLog'),
 ('if (!callbackAdmission.submit {\n                            enqueueRelayMessageCallbacks(', 'Fx39Trace.mark("target.callbackAdmission.start type=$messageType")\n                        if (!callbackAdmission.submit {\n                            enqueueRelayMessageCallbacks('),
 ('}) break\n\n                        when (message)', '}) break\n                        Fx39Trace.mark("target.callbackAdmission.end type=$messageType")\n\n                        when (message)'),
 ('val depthAfterAccept = pendingProcessing.incrementAndGet()', 'Fx39Trace.mark("relay.frame.received type=${json.optString("type")} id=${json.optString("requestId")} depth=$depthBeforeAccept")\n        val depthAfterAccept = pendingProcessing.incrementAndGet()'),
 ('messageProcessingMutex.withLock {\n                        val outcome', 'Fx39Trace.mark("relay.frame.worker type=${json.optString("type")} id=${json.optString("requestId")}")\n                    messageProcessingMutex.withLock {\n                        Fx39Trace.mark("relay.frame.locked type=${json.optString("type")} id=${json.optString("requestId")}")\n                        val outcome'),
 ('registeredPeerId = outcome.registeredPeerId\n                        synchronousFailure', 'Fx39Trace.mark("relay.frame.processed type=${json.optString("type")} id=${json.optString("requestId")}")\n                        registeredPeerId = outcome.registeredPeerId\n                        synchronousFailure'),
]
for old,new in edits:edit(f,old,new)
f=root/'src/foundation/url/resolver/UrlResolver.kt'
edit(f,'import foundation.url.protocol.', 'import foundation.url.protocol.Fx39Trace\nimport foundation.url.protocol.')
for old,new in [
 ('val requestJson = Libp2pRpcProtocol.readMessage(requestInput)','val requestJson = Libp2pRpcProtocol.readMessage(requestInput)\n                Fx39Trace.mark("adapter.request.read id=${requestJson.optString("id")}")'),
 ('forward(requestJson, requestBudget)','Fx39Trace.mark("adapter.forward.start id=$requestId")\n                    forward(requestJson, requestBudget).also { Fx39Trace.mark("adapter.forward.end id=$requestId") }'),
 ('rpcReadExecutor.submit(Callable { handleRelayedRequestOnWorker(request) })', 'rpcReadExecutor.submit(Callable { Fx39Trace.mark("resolver.handler.worker id=${request.requestId}"); handleRelayedRequestOnWorker(request) })'),
 ('val task = synchronized(relayRequestHandlerTaskLock) {','Fx39Trace.mark("resolver.handler.enqueue id=${request.requestId}")\n        val task = synchronized(relayRequestHandlerTaskLock) {'),
 ('pendingRequest.responseFuture.complete(json)','Fx39Trace.mark("caller.response.correlated id=$id")\n                            pendingRequest.responseFuture.complete(json)'),
 ('val responseBytes = service.sendViaRelay(\n                    relayPeerId = candidate.peerId,','Fx39Trace.mark("resolver.sendViaRelay.start relay=${candidate.peerId}")\n                val responseBytes = service.sendViaRelay(\n                    relayPeerId = candidate.peerId,'),
]:edit(f,old,new)
original='testPeerScopedAliasCircuitOnlyUnreservedRelayReproducesNothingToComplete'
s=(root/('tests/'+original+'.kts')).read_text()
s=re.sub(r'^@TestWorkerSlots\(\d+\)\n','',s,flags=re.M)
for n in range(4):
 name='testFx39Diagnostic'+str(n)
 text=s.replace('fun '+original+'()', 'fun '+name+'()')
 text=text.replace('import foundation.url.protocol.BootstrapPeer','import foundation.url.protocol.Fx39Trace\nimport foundation.url.protocol.BootstrapPeer')
 text=text.replace('targetHandlerCalls.incrementAndGet()', 'Fx39Trace.mark("application.handler.enter"); targetHandlerCalls.incrementAndGet()')
 text=text.replace('val response = connection.sendRequest("ping", emptyMap(), timeoutMs = 1_000)', 'Fx39Trace.mark("application.request.start")\n                val response = connection.sendRequest("ping", emptyMap(), timeoutMs = 30_000)\n                Fx39Trace.mark("application.reply.arrived")')
 text=text.replace('        PublicAddressDiscovery.clear()','        Fx39Trace.dump(requireNotNull(System.getenv("FX39_TIMING_DIR")) + "/'+name+'.txt")\n        PublicAddressDiscovery.clear()')
 (root/('tests/'+name+'.kts')).write_text(text)
print('Instrumented current Resolver and Protocol; four diagnostic scenarios generated')
