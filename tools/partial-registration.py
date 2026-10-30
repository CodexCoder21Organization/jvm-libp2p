from pathlib import Path
import subprocess
repo=Path('/code/ws/W4/workspace/jvm-libp2p-012450')
name='libp2p/src/test/kotlin/io/libp2p/protocol/PingConcurrentLifetimeTest.kt'
s=subprocess.check_output(['git','show','HEAD:'+name],cwd=repo,text=True)
s=s.replace('import java.util.concurrent.atomic.AtomicBoolean\n','import java.util.concurrent.atomic.AtomicBoolean\nimport java.util.concurrent.atomic.AtomicInteger\n')
s=s.replace('''            repeat(4) { round ->
                val failedRoots''','''            repeat(4) { round ->
                binding.installBeforeFailure.set(round % 2 == 1)
                val failedRoots''')
s=s.replace('''            assertThat(binding.initializationFailures).hasSize(32)
''','''            assertThat(binding.initializationFailures).hasSize(32)
            assertThat(binding.installedFailures.get()).isEqualTo(16)
''')
s=s.replace('''    private val delegate: Stream,
    private val failure: Throwable
) : Stream by delegate {''','''    private val delegate: Stream,
    private val failure: Throwable,
    private val installBeforeFailure: Boolean,
    private val onInstalled: () -> Unit
) : Stream by delegate {''')
s=s.replace('''    override fun <TMessage> pushHandler(protocolHandler: ProtocolMessageHandler<TMessage>) {
        throw failure
''','''    override fun <TMessage> pushHandler(protocolHandler: ProtocolMessageHandler<TMessage>) {
        if (installBeforeFailure) {
            delegate.pushHandler(protocolHandler)
            onInstalled()
        }
        throw failure
''')
s=s.replace('''    override fun pushHandler(handler: ChannelHandler) {
        throw failure
''','''    override fun pushHandler(handler: ChannelHandler) {
        if (installBeforeFailure) {
            delegate.pushHandler(handler)
            onInstalled()
        }
        throw failure
''')
s=s.replace('''    val initializationFailures = ConcurrentLinkedQueue<Throwable>()
''','''    val initializationFailures = ConcurrentLinkedQueue<Throwable>()
    val installBeforeFailure = AtomicBoolean(false)
    val installedFailures = AtomicInteger()
''')
s=s.replace('''super.initChannel(PingPushHandlerFailureStream(ch as Stream, failure), selectedProtocol)''','''super.initChannel(
                    PingPushHandlerFailureStream(ch as Stream, failure, installBeforeFailure.get()) {
                        installedFailures.incrementAndGet()
                    },
                    selectedProtocol
                )''')
assert s.count('binding.installBeforeFailure.set')==1
Path('/tmp/W4-012450-PingConcurrentNext.kt').write_text(s)
