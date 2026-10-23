from pathlib import Path
p = Path("libp2p/src/test/kotlin/io/libp2p/security/noise/NoiseHandshakeOffEventLoopTest.kt")
s = p.read_text()
old = """                promise.setSuccess()
                return
"""
new = """                // A silent response has not been sent, so the peer must not start its next read timer.
                ctx.channel().closeFuture().addListener {
                    promise.tryFailure(ClosedChannelException())
                }
                return
"""
assert s.count(old) == 1
s = s.replace(old, new)
lines = s.splitlines()
imports = [line for line in lines if line.startswith("import ")]
first = next(i for i, line in enumerate(lines) if line.startswith("import "))
last = max(i for i, line in enumerate(lines) if line.startswith("import "))
normal = sorted(line for line in imports if not line.startswith(("import java.", "import javax.", "import kotlin.")))
standard = sorted(line for line in imports if line.startswith(("import java.", "import javax.", "import kotlin.")))
lines[first:last + 1] = normal + standard
p.write_text("\n".join(lines) + "\n")
