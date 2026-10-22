package io.libp2p.mux.mplex

import io.libp2p.core.Stream
import io.libp2p.core.StreamHandler
import io.libp2p.core.multistream.MultistreamProtocolV1
import io.libp2p.etc.types.fromHex
import io.libp2p.etc.types.toHex
import io.libp2p.mux.MuxHandler
import io.libp2p.mux.MuxHandlerAbstractTest
import io.libp2p.mux.MuxHandlerAbstractTest.AbstractTestMuxFrame.Flag.*
import io.libp2p.tools.TestChannel
import io.libp2p.tools.readAllBytesAndRelease
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
