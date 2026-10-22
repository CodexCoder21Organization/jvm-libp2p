package io.libp2p.core

/** The peer reached its stream capacity before admitting this child. */
class StreamCapacityException(message: String) : Libp2pException(message)
