from pathlib import Path
p=Path('libp2p/src/main/kotlin/io/libp2p/etc/util/netty/mux/MuxChannel.kt')
s=p.read_text().replace('    override fun metadata(): ChannelMetadata = ChannelMetadata(true)', '''    override fun doBeginRead() {
        parent.childReadRequested(this)
    }

    override fun metadata(): ChannelMetadata = ChannelMetadata(true)''')
p.write_text(s)
p=Path('libp2p/src/main/kotlin/io/libp2p/etc/util/netty/mux/AbstractMuxHandler.kt')
s=p.read_text().replace('        var draining = false','        var draining = false\n        var readPending = false',1)
s=s.replace('if (autoRead && queued != null && !queued.draining) {', '''if (queued != null && queued.messages.isNotEmpty() &&
                        (autoRead || queued.readPending) && !queued.draining
                    ) {''')
s=s.replace('if (child.config().isAutoRead) drainPendingInbound(child)','if (child.config().isAutoRead || pending.readPending) drainPendingInbound(child)',1)
start=s.index('    private fun drainPendingInbound(child: MuxChannel<TData>) {')
end=s.index('\n    private fun deliverToChild(',start)
s=s[:start]+'''    /** Netty coalesces outstanding reads: demand is a flag, not an accumulating allowance. */
    internal fun childReadRequested(child: MuxChannel<TData>) {
        if (!child.isOpen || child.remoteDisconnected || child.config().isAutoRead) return
        val queued = pendingInbound.getOrPut(child.id) { PendingInbound() }
        queued.readPending = true
        drainPendingInbound(child)
        requestParentReadIfNeeded()
    }

    private fun requestParentReadIfNeeded() {
        val parentChannel = getChannelHandlerContext().channel()
        if (parentChannel.isOpen && !parentChannel.config().isAutoRead &&
            pendingInbound.values.any { it.readPending && !it.draining }
        ) {
            parentChannel.read()
        }
    }

    private fun drainPendingInbound(child: MuxChannel<TData>) {
        val queued = pendingInbound[child.id] ?: return
        if (queued.draining) return
        // Keep the guard through read-complete too: callbacks may request another read.
        queued.draining = true
        try {
            while (child.isOpen && queued.messages.isNotEmpty() &&
                (child.config().isAutoRead || queued.readPending)
            ) {
                val manualRead = !child.config().isAutoRead
                // Consume before calling application code so re-entry can arm the next read.
                queued.readPending = false
                do {
                    val msg = queued.messages.removeFirst()
                    queued.bytes -= pendingChildReadSize(msg)
                    sealPendingChildRead(msg)
                    deliverToChild(child, msg, true)
                } while (!manualRead && child.isOpen && child.config().isAutoRead && queued.messages.isNotEmpty())
                if (child.isOpen) child.pipeline().fireChannelReadComplete()
            }
        } finally {
            queued.draining = false
            val remoteEndReady = queued.messages.isEmpty() && queued.remoteEndPending
            if (queued.messages.isEmpty() && !queued.readPending && !remoteEndReady && pendingInbound[child.id] === queued) {
                pendingInbound.remove(child.id)
            }
            if (remoteEndReady) {
                try {
                    if (child.isOpen) child.onRemoteDisconnected()
                } finally {
                    if (pendingInbound[child.id] === queued) pendingInbound.remove(child.id)
                }
            }
        }
    }
''' +s[end:]
s=s.replace('        pendingReadComplete.clear()\n    }','''        pendingReadComplete.clear()
        // A physical read can end partway through an encoded frame, or contain only siblings.
        // Keep a paused transport reading until the requested child receives its payload.
        requestParentReadIfNeeded()
    }''',1)
s=s.replace('if (child.config().isAutoRead) drainPendingInbound(child)','if (child.config().isAutoRead || queued.readPending) drainPendingInbound(child)')
p.write_text(s)
