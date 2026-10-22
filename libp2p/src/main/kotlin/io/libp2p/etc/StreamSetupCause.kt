package io.libp2p.etc

import io.netty.util.AttributeKey

/** Recorded before completing setup futures so close callbacks retain the same result. */
internal val STREAM_SETUP_CAUSE: AttributeKey<Throwable> = AttributeKey.valueOf("libp2p.streamSetupCause")
