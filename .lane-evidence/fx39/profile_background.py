from pathlib import Path
p=Path('src/foundation/url/protocol/Fx39Trace.kt')
s=p.read_text().replace('    private val events =','''    private val os = java.lang.management.ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
    private val stats = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLongArray>()
    fun begin(): LongArray = longArrayOf(System.nanoTime(), cpu.currentThreadCpuTime)
    fun end(name: String, started: LongArray) {
        val wall = System.nanoTime()-started[0]
        val used = cpu.currentThreadCpuTime
        val value = stats.computeIfAbsent(name) { java.util.concurrent.atomic.AtomicLongArray(3) }
        value.incrementAndGet(0)
        value.addAndGet(1, wall)
        if (started[1]>=0 && used>=0) value.addAndGet(2, used-started[1])
    }
    private val events =''')
s=s.replace('classes=${loading.totalLoadedClassCount}', 'classes=${loading.totalLoadedClassCount} processCpu=${os.processCpuTime}')
s=s.replace('        java.nio.file.Files.write(java.nio.file.Paths.get(path), events.toList())', '''        java.nio.file.Files.write(java.nio.file.Paths.get(path), events.toList())
        java.nio.file.Files.write(java.nio.file.Paths.get(path+".metrics"), stats.entries.sortedBy { it.key }.map { (name,value) ->
            "$name count=${value.get(0)} wallNanos=${value.get(1)} cpuNanos=${value.get(2)}"
        })''')
p.write_text(s)
p=Path('src/foundation/url/resolver/UrlResolver.kt');s=p.read_text()
functions=[
 '    override fun onServiceAnnouncement(announcement: GossipProtocol.ServiceAnnouncement) {',
 '    override fun onPeerAnnouncement(announcement: GossipProtocol.PeerAnnouncement) {',
 '    override fun onRelayAnnouncement(announcement: RelayProtocol.RelayAnnouncement) {',
 '    override fun onPeersReceived(peers: List<PeerInfo>) {',
 '    private fun broadcastGossipMessage(\n',
 '    private fun activelyConnectedPeerIds(\n',
 '    private fun isSameHostPeer(peerId: String): Boolean {',
 '    private fun addPeerWithEvidence(\n',
 '    private fun encodeGossipMessageForTransport(message: JSONObject): ByteArray {',
 '        private fun verify(peerId: String) {',
 '    private fun verifyPeerReachabilityAttempts(\n',
]
for prefix in functions:
 assert s.count(prefix)==1,(prefix,s.count(prefix))
 a=s.index(prefix);indent=len(prefix)-len(prefix.lstrip());opening=s.index('{',a);closing=s.index('\n'+' '*indent+'}',opening)
 name=prefix.split('fun ')[1].split('(')[0]
 s=s[:closing]+'\n'+(' '*(indent+4))+'} finally { Fx39Trace.end("'+name+'", fx39Started) }'+s[closing:]
 s=s[:opening+1]+'\n'+(' '*(indent+4))+'val fx39Started = Fx39Trace.begin()\n'+(' '*(indent+4))+'try {'+s[opening+1:]
p.write_text(s)
for p in Path('tests').glob('testFx39Diagnostic*.kts'):
 s=p.read_text().replace('Fx39Trace.mark("application.request.start")','Fx39Trace.mark("gossip.before=" + (relays + listOf(target, client)).map { it.getGossipWorkDiagnostics() })\n                Fx39Trace.mark("application.request.start")')
 s=s.replace('Fx39Trace.mark("application.reply.arrived")','Fx39Trace.mark("application.reply.arrived")\n                Fx39Trace.mark("gossip.after=" + (relays + listOf(target, client)).map { it.getGossipWorkDiagnostics() })')
 p.write_text(s)
