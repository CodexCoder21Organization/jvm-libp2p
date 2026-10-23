from pathlib import Path
p=Path('libp2p/src/main/kotlin/io/libp2p/security/noise/NoiseXXSecureChannel.kt')
s=p.read_text()
a='    if (!executionObserved) {'
b='''    if (java.lang.Boolean.getBoolean("noise2.trace")) {
      System.err.println("NOISE2 admission channel=${ctx.channel().id().asLongText()} role=$role frames=$receivedHandshakeFrames local=${ctx.channel().localAddress()} remote=${ctx.channel().remoteAddress()} pending=$pendingOnLoop tasks=${loopExecutor?.pendingTasks()} workers=$offloadCrypto observed=$executionObserved")
    }
    if (!executionObserved) {'''
assert s.count(a)==1 and 'noise2.trace' not in s
s=s.replace(a,b)
a='    val next = tail.thenRunAsync('
b='''    if (java.lang.Boolean.getBoolean("noise2.trace")) {
      System.err.println("NOISE2 selected channel=${ctx.channel().id().asLongText()} role=$role frames=$receivedHandshakeFrames executor=${taskExecutor.javaClass.name}")
    }
    val next = tail.thenRunAsync('''
assert s.count(a)==1
p.write_text(s.replace(a,b))
