from pathlib import Path
p=Path('libp2p/src/main/kotlin/io/libp2p/protocol/Ping.kt')
s=p.read_text()
old='timeoutTasks.values.forEach { it.cancel(false) }'
assert s.count(old)==1
p.write_text(s.replace(old,'// Mutation: leave closed initiator timeout tasks queued.'))
