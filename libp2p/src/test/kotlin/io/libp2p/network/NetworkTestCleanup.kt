package io.libp2p.network

import io.libp2p.core.Host
import java.util.concurrent.TimeUnit

internal fun stopHostsPreservingFirstFailure(hosts: List<Host>) {
    var firstFailure: Throwable? = null
    hosts.asReversed().forEach { host ->
        try {
            host.stop().get(10, TimeUnit.SECONDS)
        } catch (error: Throwable) {
            val previousFailure = firstFailure
            if (previousFailure == null) {
                firstFailure = error
            } else if (previousFailure !== error) {
                previousFailure.addSuppressed(error)
            }
        }
    }
    firstFailure?.let { throw it }
}
