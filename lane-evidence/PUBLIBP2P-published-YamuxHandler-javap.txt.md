```text
Compiled from "YamuxHandler.kt"
public class io.libp2p.mux.yamux.YamuxHandler extends io.libp2p.mux.MuxHandler {
  private final io.libp2p.core.multistream.MultistreamProtocol multistreamProtocol;

  private final int maxFrameDataLength;

  private final boolean connectionInitiator;

  private final int maxBufferedConnectionWrites;

  private final int ackBacklogLimit;

  private final int initialWindowSize;

  private final io.libp2p.mux.yamux.YamuxStreamIdGenerator idGenerator;

  private final java.util.Map<io.libp2p.etc.util.netty.mux.MuxId, io.libp2p.mux.yamux.YamuxHandler$YamuxStreamHandler> streamHandlers;

  private long pendingChildWriteBytes;

  private long activeChildWriteBytes;

  private final java.util.IdentityHashMap<io.netty.buffer.ByteBuf, java.lang.Integer> activeChildWrites;

  private java.lang.Long firstUnwritableNanos;

  private io.libp2p.mux.YamuxOutboundBufferExceededException terminalWriteFailure;

  private final java.util.concurrent.CompletableFuture<java.lang.Long> goAwayPromise;

  public io.libp2p.mux.yamux.YamuxHandler(io.libp2p.core.multistream.MultistreamProtocol, int, java.util.concurrent.CompletableFuture<io.libp2p.core.mux.StreamMuxer$Session>, io.libp2p.core.StreamHandler<?>, boolean, int, int, int);
    Code:
       0: aload_1
       1: ldc           #11                 // String multistreamProtocol
       3: invokestatic  #17                 // Method kotlin/jvm/internal/Intrinsics.checkNotNullParameter:(Ljava/lang/Object;Ljava/lang/String;)V
       6: aload         4
       8: ldc           #19                 // String inboundStreamHandler
      10: invokestatic  #17                 // Method kotlin/jvm/internal/Intrinsics.checkNotNullParameter:(Ljava/lang/Object;Ljava/lang/String;)V
      13: aload_0
      14: aload_3
      15: aload         4
      17: iconst_0
      18: iconst_4
      19: aconst_null
      20: invokespecial #22                 // Method io/libp2p/mux/MuxHandler."<init>":(Ljava/util/concurrent/CompletableFuture;Lio/libp2p/core/StreamHandler;IILkotlin/jvm/internal/DefaultConstructorMarker;)V
      23: aload_0
      24: aload_1
      25: putfield      #25                 // Field multistreamProtocol:Lio/libp2p/core/multistream/MultistreamProtocol;
      28: aload_0
      29: iload_2
      30: putfield      #29                 // Field maxFrameDataLength:I
      33: aload_0
      34: iload         5
      36: putfield      #33                 // Field connectionInitiator:Z
      39: aload_0
      40: iload         6
      42: putfield      #36                 // Field maxBufferedConnectionWrites:I
      45: aload_0
      46: iload         7
      48: putfield      #39                 // Field ackBacklogLimit:I
      51: aload_0
      52: iload         8
      54: putfield      #42                 // Field initialWindowSize:I
      57: aload_0
      58: new           #44                 // class io/libp2p/mux/yamux/YamuxStreamIdGenerator
      61: dup
      62: aload_0
      63: getfield      #33                 // Field connectionInitiator:Z
      66: invokespecial #47                 // Method io/libp2p/mux/yamux/YamuxStreamIdGenerator."<init>":(Z)V
      69: putfield      #51                 // Field idGenerator:Lio/libp2p/mux/yamux/YamuxStreamIdGenerator;
      72: aload_0
      73: new           #53                 // class java/util/concurrent/ConcurrentHashMap
      76: dup
      77: invokespecial #56                 // Method java/util/concurrent/ConcurrentHashMap."<init>":()V
      80: checkcast     #58                 // class java/util/Map
      83: putfield      #62                 // Field streamHandlers:Ljava/util/Map;
      86: aload_0
      87: new           #64                 // class java/util/IdentityHashMap
      90: dup
      91: invokespecial #65                 // Method java/util/IdentityHashMap."<init>":()V
      94: putfield      #69                 // Field activeChildWrites:Ljava/util/IdentityHashMap;
      97: aload_0
      98: new           #71                 // class java/util/concurrent/CompletableFuture
     101: dup
     102: invokespecial #72                 // Method java/util/concurrent/CompletableFuture."<init>":()V
     105: putfield      #76                 // Field goAwayPromise:Ljava/util/concurrent/CompletableFuture;
     108: return

  public io.libp2p.mux.yamux.YamuxHandler(io.libp2p.core.multistream.MultistreamProtocol, int, java.util.concurrent.CompletableFuture, io.libp2p.core.StreamHandler, boolean, int, int, int, int, kotlin.jvm.internal.DefaultConstructorMarker);
    Code:
       0: iload         9
       2: sipush        128
       5: iand
       6: ifeq          13
       9: ldc           #82                 // int 262144
      11: istore        8
      13: aload_0
      14: aload_1
      15: iload_2
      16: aload_3
      17: aload         4
      19: iload         5
      21: iload         6
      23: iload         7
      25: iload         8
      27: invokespecial #84                 // Method "<init>":(Lio/libp2p/core/multistream/MultistreamProtocol;ILjava/util/concurrent/CompletableFuture;Lio/libp2p/core/StreamHandler;ZIII)V
      30: return

  protected io.libp2p.core.multistream.MultistreamProtocol getMultistreamProtocol();
    Code:
       0: aload_0
       1: getfield      #25                 // Field multistreamProtocol:Lio/libp2p/core/multistream/MultistreamProtocol;
       4: areturn

  protected int getMaxFrameDataLength();
    Code:
       0: aload_0
       1: getfield      #29                 // Field maxFrameDataLength:I
       4: ireturn

  public final java.util.concurrent.CompletableFuture<java.lang.Long> getGoAwayPromise();
    Code:
       0: aload_0
       1: getfield      #76                 // Field goAwayPromise:Ljava/util/concurrent/CompletableFuture;
       4: areturn

  private final io.libp2p.mux.yamux.YamuxHandler$YamuxStreamHandler getStreamHandlerOrThrow(io.libp2p.etc.util.netty.mux.MuxId);
    Code:
       0: aload_0
       1: aload_1
       2: aconst_null
       3: invokespecial #97                 // Method getStreamHandlerOrReleaseAndThrow:(Lio/libp2p/etc/util/netty/mux/MuxId;Lio/netty/buffer/ByteBuf;)Lio/libp2p/mux/yamux/YamuxHandler$YamuxStreamHandler;
       6: areturn

  private final io.libp2p.mux.yamux.YamuxHandler$YamuxStreamHandler getStreamHandlerOrReleaseAndThrow(io.libp2p.etc.util.netty.mux.MuxId, io.netty.buffer.ByteBuf);
    Code:
       0: aload_0
       1: getfield      #62                 // Field streamHandlers:Ljava/util/Map;
       4: aload_1
       5: invokeinterface #103,  2          // InterfaceMethod java/util/Map.get:(Ljava/lang/Object;)Ljava/lang/Object;
      10: checkcast     #105                // class io/libp2p/mux/yamux/YamuxHandler$YamuxStreamHandler
      13: dup
      14: ifnonnull     44
      17: pop
      18: aload_0
      19: checkcast     #2                  // class io/libp2p/mux/yamux/YamuxHandler
      22: astore_3
      23: iconst_0
      24: istore        4
      26: aload_2
      27: ifnull        35
      30: aload_3
      31: aload_2
      32: invokevirtual #109                // Method releaseMessage:(Lio/netty/buffer/ByteBuf;)V
      35: new           #111                // class io/libp2p/mux/UnknownStreamIdMuxerException
      38: dup
      39: aload_1
      40: invokespecial #114                // Method io/libp2p/mux/UnknownStreamIdMuxerException."<init>":(Lio/libp2p/etc/util/netty/mux/MuxId;)V
      43: athrow
      44: areturn

  public void channelUnregistered(io.netty.channel.ChannelHandlerContext);
    Code:
       0: aload_0
       1: getfield      #62                 // Field streamHandlers:Ljava/util/Map;
       4: invokeinterface #128,  1          // InterfaceMethod java/util/Map.values:()Ljava/util/Collection;
       9: checkcast     #130                // class java/lang/Iterable
      12: astore_2
      13: iconst_0
      14: istore_3
      15: aload_2
      16: invokeinterface #134,  1          // InterfaceMethod java/lang/Iterable.iterator:()Ljava/util/Iterator;
      21: astore        4
      23: aload         4
      25: invokeinterface #140,  1          // InterfaceMethod java/util/Iterator.hasNext:()Z
      30: ifeq          63
      33: aload         4
      35: invokeinterface #144,  1          // InterfaceMethod java/util/Iterator.next:()Ljava/lang/Object;
      40: astore        5
      42: aload         5
      44: checkcast     #105                // class io/libp2p/mux/yamux/YamuxHandler$YamuxStreamHandler
      47: astore        6
      49: iconst_0
      50: istore        7
      52: aload         6
      54: aconst_null
      55: iconst_1
      56: aconst_null
      57: invokestatic  #148                // Method io/libp2p/mux/yamux/YamuxHandler$YamuxStreamHandler.dispose$default:(Lio/libp2p/mux/yamux/YamuxHandler$YamuxStreamHandler;Ljava/lang/Throwable;ILjava/lang/Object;)V
      60: goto          23
      63: nop
      64: aload_0
      65: getfield      #76                 // Field goAwayPromise:Ljava/util/concurrent/CompletableFuture;
      68: invokevirtual #151                // Method java/util/concurrent/CompletableFuture.isDone:()Z
      71: ifne          94
      74: aload_0
      75: getfield      #76                 // Field goAwayPromise:Ljava/util/concurrent/CompletableFuture;
      78: new           #153                // class io/libp2p/core/ConnectionClosedException
      81: dup
      82: ldc           #155                // String Connection was closed without Go Away message
      84: invokespecial #158                // Method io/libp2p/core/ConnectionClosedException."<init>":(Ljava/lang/String;)V
      87: checkcast     #160                // class java/lang/Throwable
      90: invokevirtual #164                // Method java/util/concurrent/CompletableFuture.completeExceptionally:(Ljava/lang/Throwable;)Z
      93: pop
      94: aload_0
      95: aload_1
      96: invokespecial #166                // Method io/libp2p/mux/MuxHandler.channelUnregistered:(Lio/netty/channel/ChannelHandlerContext;)V
      99: return

  public void channelRead(io.netty.channel.ChannelHandlerContext, java.lang.Object);
    Code:
       0: aload_1
       1: ldc           #179                // String ctx
       3: invokestatic  #17                 // Method kotlin/jvm/internal/Intrinsics.checkNotNullParameter:(Ljava/lang/Object;Ljava/lang/String;)V
       6: aload_2
       7: ldc           #181                // String msg
       9: invokestatic  #17                 // Method kotlin/jvm/internal/Intrinsics.checkNotNullParameter:(Ljava/lang/Object;Ljava/lang/String;)V
      12: aload_2
      13: checkcast     #183                // class io/libp2p/mux/yamux/YamuxFrame
      16: pop
      17: aload_2
      18: checkcast     #183                // class io/libp2p/mux/yamux/YamuxFrame
      21: invokevirtual #187                // Method io/libp2p/mux/yamux/YamuxFrame.getType:()Lio/libp2p/mux/yamux/YamuxType;
      24: getstatic     #193                // Field io/libp2p/mux/yamux/YamuxHandler$WhenMappings.$EnumSwitchMapping$0:[I
      27: swap
      28: invokevirtual #198                // Method io/libp2p/mux/yamux/YamuxType.ordinal:()I
      31: iaload
      32: tableswitch   { // 1 to 2
                     1: 56
                     2: 67
               default: 78
          }
      56: aload_0
      57: aload_2
      58: checkcast     #183                // class io/libp2p/mux/yamux/YamuxFrame
      61: invokespecial #202                // Method handlePing:(Lio/libp2p/mux/yamux/YamuxFrame;)V
      64: goto          161
      67: aload_0
      68: aload_2
      69: checkcast     #183                // class io/libp2p/mux/yamux/YamuxFrame
      72: invokespecial #205                // Method handleGoAway:(Lio/libp2p/mux/yamux/YamuxFrame;)V
      75: goto          161
      78: aload_2
      79: checkcast     #183                // class io/libp2p/mux/yamux/YamuxFrame
      82: invokevirtual #209                // Method io/libp2p/mux/yamux/YamuxFrame.getFlags:()Ljava/util/Set;
      85: getstatic     #215                // Field io/libp2p/mux/yamux/YamuxFlag.SYN:Lio/libp2p/mux/yamux/YamuxFlag;
      88: invokeinterface #221,  2          // InterfaceMethod java/util/Set.contains:(Ljava/lang/Object;)Z
      93: ifeq          136
      96: nop
      97: aload_0
      98: aload_2
      99: checkcast     #183                // class io/libp2p/mux/yamux/YamuxFrame
     102: invokevirtual #225                // Method io/libp2p/mux/yamux/YamuxFrame.getId:()Lio/libp2p/etc/util/netty/mux/MuxId;
     105: invokespecial #228                // Method validateSynRemoteMuxId:(Lio/libp2p/etc/util/netty/mux/MuxId;)V
     108: aload_0
     109: aload_2
     110: checkcast     #183                // class io/libp2p/mux/yamux/YamuxFrame
     113: invokevirtual #225                // Method io/libp2p/mux/yamux/YamuxFrame.getId:()Lio/libp2p/etc/util/netty/mux/MuxId;
     116: invokespecial #231                // Method onRemoteYamuxOpen:(Lio/libp2p/etc/util/netty/mux/MuxId;)V
     119: goto          136
     122: astore_3
     123: aload_2
     124: checkcast     #183                // class io/libp2p/mux/yamux/YamuxFrame
     127: invokevirtual #235                // Method io/libp2p/mux/yamux/YamuxFrame.getData:()Lio/netty/buffer/ByteBuf;
     130: invokestatic  #240                // Method io/netty/util/ReferenceCountUtil.release:(Ljava/lang/Object;)Z
     133: pop
     134: aload_3
     135: athrow
     136: aload_0
     137: aload_2
     138: checkcast     #183                // class io/libp2p/mux/yamux/YamuxFrame
     141: invokevirtual #225                // Method io/libp2p/mux/yamux/YamuxFrame.getId:()Lio/libp2p/etc/util/netty/mux/MuxId;
     144: aload_2
     145: checkcast     #183                // class io/libp2p/mux/yamux/YamuxFrame
     148: invokevirtual #235                // Method io/libp2p/mux/yamux/YamuxFrame.getData:()Lio/netty/buffer/ByteBuf;
     151: invokespecial #97                 // Method getStreamHandlerOrReleaseAndThrow:(Lio/libp2p/etc/util/netty/mux/MuxId;Lio/netty/buffer/ByteBuf;)Lio/libp2p/mux/yamux/YamuxHandler$YamuxStreamHandler;
     154: aload_2
     155: checkcast     #183                // class io/libp2p/mux/yamux/YamuxFrame
     158: invokevirtual #243                // Method io/libp2p/mux/yamux/YamuxHandler$YamuxStreamHandler.handleFrameRead:(Lio/libp2p/mux/yamux/YamuxFrame;)V
     161: return
    Exception table:
       from    to  target type
          96   119   122   Class java/lang/Throwable

  private final io.netty.channel.ChannelFuture writeAndFlushFrame(io.libp2p.mux.yamux.YamuxFrame);
    Code:
       0: aload_0
       1: getfield      #251                // Field terminalWriteFailure:Lio/libp2p/mux/YamuxOutboundBufferExceededException;
       4: astore_2
       5: aload_2
       6: ifnull        19
       9: aload_1
      10: invokevirtual #235                // Method io/libp2p/mux/yamux/YamuxFrame.getData:()Lio/netty/buffer/ByteBuf;
      13: invokestatic  #240                // Method io/netty/util/ReferenceCountUtil.release:(Ljava/lang/Object;)Z
      16: pop
      17: aload_2
      18: athrow
      19: aload_0
      20: invokevirtual #255                // Method getChannelHandlerContext:()Lio/netty/channel/ChannelHandlerContext;
      23: astore_3
      24: aload_3
      25: invokeinterface #261,  1          // InterfaceMethod io/netty/channel/ChannelHandlerContext.channel:()Lio/netty/channel/Channel;
      30: astore        4
      32: aload         4
      34: invokeinterface #267,  1          // InterfaceMethod io/netty/channel/Channel.unsafe:()Lio/netty/channel/Channel$Unsafe;
      39: invokeinterface #273,  1          // InterfaceMethod io/netty/channel/Channel$Unsafe.outboundBuffer:()Lio/netty/channel/ChannelOutboundBuffer;
      44: astore        5
      46: aload         5
      48: dup
      49: ifnull        58
      52: invokevirtual #279                // Method io/netty/channel/ChannelOutboundBuffer.totalPendingWriteBytes:()J
      55: goto          60
      58: pop
      59: lconst_0
      60: lstore        6
      62: ldc2_w        #280                // long 12l
      65: aload_1
      66: invokevirtual #235                // Method io/libp2p/mux/yamux/YamuxFrame.getData:()Lio/netty/buffer/ByteBuf;
      69: dup
      70: ifnull        80
      73: invokevirtual #284                // Method io/netty/buffer/ByteBuf.readableBytes:()I
      76: i2l
      77: goto          82
      80: pop
      81: lconst_0
      82: ladd
      83: lstore        8
      85: aload_0
      86: getfield      #288                // Field pendingChildWriteBytes:J
      89: aload_0
      90: getfield      #291                // Field activeChildWriteBytes:J
      93: lsub
      94: lstore        10
      96: lload         10
      98: lconst_0
      99: lcmp
     100: iflt          107
     103: iconst_1
     104: goto          108
     107: iconst_0
     108: ifne          143
     111: iconst_0
     112: istore        13
     114: aload_0
     115: getfield      #291                // Field activeChildWriteBytes:J
     118: aload_0
     119: getfield      #288                // Field pendingChildWriteBytes:J
     122: invokedynamic #303,  0            // InvokeDynamic #0:makeConcatWithConstants:(JJ)Ljava/lang/String;
     127: nop
     128: astore        13
     130: new           #305                // class java/lang/IllegalStateException
     133: dup
     134: aload         13
     136: invokevirtual #311                // Method java/lang/Object.toString:()Ljava/lang/String;
     139: invokespecial #312                // Method java/lang/IllegalStateException."<init>":(Ljava/lang/String;)V
     142: athrow
     143: lload         6
     145: lload         8
     147: ladd
     148: lload         10
     150: ladd
     151: aload_0
     152: invokespecial #315                // Method calculateTotalBufferedWrites:()I
     155: i2l
     156: ladd
     157: lstore        12
     159: aload_0
     160: lload         6
     162: invokespecial #319                // Method updateUnwritableStart:(J)V
     165: lload         12
     167: aload_0
     168: getfield      #36                 // Field maxBufferedConnectionWrites:I
     171: i2l
     172: lcmp
     173: ifle          274
     176: new           #321                // class io/libp2p/mux/YamuxOutboundBufferExceededException
     179: dup
     180: aload_0
     181: aload_3
     182: invokespecial #325                // Method describeRemotePeer:(Lio/netty/channel/ChannelHandlerContext;)Ljava/lang/String;
     185: lload         6
     187: lload         8
     189: lload         12
     191: aload_0
     192: getfield      #36                 // Field maxBufferedConnectionWrites:I
     195: aload_0
     196: invokespecial #328                // Method currentUnwritableDurationMillis:()J
     199: aload         4
     201: invokedynamic #333,  0            // InvokeDynamic #1:makeConcatWithConstants:(Ljava/lang/String;JJJIJLio/netty/channel/Channel;)Ljava/lang/String;
     206: invokespecial #334                // Method io/libp2p/mux/YamuxOutboundBufferExceededException."<init>":(Ljava/lang/String;)V
     209: astore        14
     211: aload_0
     212: aload         14
     214: putfield      #251                // Field terminalWriteFailure:Lio/libp2p/mux/YamuxOutboundBufferExceededException;
     217: aload         4
     219: invokestatic  #340                // Method io/libp2p/etc/AttributesKt.getWRITE_FAILURE:()Lio/netty/util/AttributeKey;
     222: invokeinterface #344,  2          // InterfaceMethod io/netty/channel/Channel.attr:(Lio/netty/util/AttributeKey;)Lio/netty/util/Attribute;
     227: aload         14
     229: invokeinterface #350,  2          // InterfaceMethod io/netty/util/Attribute.set:(Ljava/lang/Object;)V
     234: aload_1
     235: invokevirtual #235                // Method io/libp2p/mux/yamux/YamuxFrame.getData:()Lio/netty/buffer/ByteBuf;
     238: invokestatic  #240                // Method io/netty/util/ReferenceCountUtil.release:(Ljava/lang/Object;)Z
     241: pop
     242: aload_3
     243: aload         14
     245: checkcast     #160                // class java/lang/Throwable
     248: invokeinterface #354,  2          // InterfaceMethod io/netty/channel/ChannelHandlerContext.fireExceptionCaught:(Ljava/lang/Throwable;)Lio/netty/channel/ChannelHandlerContext;
     253: pop
     254: aload_3
     255: invokeinterface #358,  1          // InterfaceMethod io/netty/channel/ChannelHandlerContext.executor:()Lio/netty/util/concurrent/EventExecutor;
     260: aload_3
     261: invokedynamic #374,  0            // InvokeDynamic #2:run:(Lio/netty/channel/ChannelHandlerContext;)Ljava/lang/Runnable;
     266: invokeinterface #380,  2          // InterfaceMethod io/netty/util/concurrent/EventExecutor.execute:(Ljava/lang/Runnable;)V
     271: aload         14
     273: athrow
     274: aload_3
     275: aload_1
     276: invokeinterface #384,  2          // InterfaceMethod io/netty/channel/ChannelHandlerContext.writeAndFlush:(Ljava/lang/Object;)Lio/netty/channel/ChannelFuture;
     281: dup
     282: ldc_w         #386                // String ctx.writeAndFlush(yamuxFrame)
     285: invokestatic  #389                // Method kotlin/jvm/internal/Intrinsics.checkNotNullExpressionValue:(Ljava/lang/Object;Ljava/lang/String;)V
     288: areturn

  private final void updateUnwritableStart(long);
    Code:
       0: aload_0
       1: invokevirtual #255                // Method getChannelHandlerContext:()Lio/netty/channel/ChannelHandlerContext;
       4: invokeinterface #261,  1          // InterfaceMethod io/netty/channel/ChannelHandlerContext.channel:()Lio/netty/channel/Channel;
       9: astore_3
      10: aload_3
      11: invokeinterface #402,  1          // InterfaceMethod io/netty/channel/Channel.isWritable:()Z
      16: ifeq          29
      19: lload_1
      20: aload_0
      21: getfield      #36                 // Field maxBufferedConnectionWrites:I
      24: i2l
      25: lcmp
      26: ifle          49
      29: aload_0
      30: getfield      #406                // Field firstUnwritableNanos:Ljava/lang/Long;
      33: ifnonnull     54
      36: aload_0
      37: invokestatic  #411                // Method java/lang/System.nanoTime:()J
      40: invokestatic  #417                // Method java/lang/Long.valueOf:(J)Ljava/lang/Long;
      43: putfield      #406                // Field firstUnwritableNanos:Ljava/lang/Long;
      46: goto          54
      49: aload_0
      50: aconst_null
      51: putfield      #406                // Field firstUnwritableNanos:Ljava/lang/Long;
      54: return

  private final long currentUnwritableDurationMillis();
    Code:
       0: aload_0
       1: getfield      #406                // Field firstUnwritableNanos:Ljava/lang/Long;
       4: dup
       5: ifnull        30
       8: checkcast     #419                // class java/lang/Number
      11: invokevirtual #422                // Method java/lang/Number.longValue:()J
      14: lstore_1
      15: iconst_0
      16: istore_3
      17: invokestatic  #411                // Method java/lang/System.nanoTime:()J
      20: lload_1
      21: lsub
      22: ldc2_w        #423                // long 1000000l
      25: ldiv
      26: nop
      27: goto          32
      30: pop
      31: lconst_0
      32: lreturn

  private final java.lang.String describeRemotePeer(io.netty.channel.ChannelHandlerContext);
    Code:
       0: aload_1
       1: invokeinterface #261,  1          // InterfaceMethod io/netty/channel/ChannelHandlerContext.channel:()Lio/netty/channel/Channel;
       6: invokestatic  #428                // Method io/libp2p/etc/AttributesKt.getCONNECTION:()Lio/netty/util/AttributeKey;
       9: invokeinterface #344,  2          // InterfaceMethod io/netty/channel/Channel.attr:(Lio/netty/util/AttributeKey;)Lio/netty/util/Attribute;
      14: invokeinterface #430,  1          // InterfaceMethod io/netty/util/Attribute.get:()Ljava/lang/Object;
      19: checkcast     #432                // class io/libp2p/core/Connection
      22: astore_2
      23: aload_2
      24: dup
      25: ifnull        51
      28: invokeinterface #436,  1          // InterfaceMethod io/libp2p/core/Connection.secureSession:()Lio/libp2p/core/security/SecureChannel$Session;
      33: dup
      34: ifnull        51
      37: invokevirtual #442                // Method io/libp2p/core/security/SecureChannel$Session.getRemoteId:()Lio/libp2p/core/PeerId;
      40: dup
      41: ifnull        51
      44: invokevirtual #445                // Method io/libp2p/core/PeerId.toString:()Ljava/lang/String;
      47: dup
      48: ifnonnull     78
      51: pop
      52: aload_1
      53: invokeinterface #261,  1          // InterfaceMethod io/netty/channel/ChannelHandlerContext.channel:()Lio/netty/channel/Channel;
      58: invokeinterface #449,  1          // InterfaceMethod io/netty/channel/Channel.remoteAddress:()Ljava/net/SocketAddress;
      63: dup
      64: ifnull        73
      67: invokevirtual #452                // Method java/net/SocketAddress.toString:()Ljava/lang/String;
      70: goto          78
      73: pop
      74: nop
      75: ldc_w         #454                // String unknown
      78: areturn

  private final void abruptlyCloseConnection();
    Code:
       0: aload_0
       1: invokevirtual #255                // Method getChannelHandlerContext:()Lio/netty/channel/ChannelHandlerContext;
       4: invokeinterface #463,  1          // InterfaceMethod io/netty/channel/ChannelHandlerContext.close:()Lio/netty/channel/ChannelFuture;
       9: pop
      10: return

  private final void validateSynRemoteMuxId(io.libp2p.etc.util.netty.mux.MuxId);
    Code:
       0: aload_0
       1: getfield      #33                 // Field connectionInitiator:Z
       4: ifne          11
       7: iconst_1
       8: goto          12
      11: iconst_0
      12: istore_2
      13: getstatic     #467                // Field io/libp2p/mux/yamux/YamuxStreamIdGenerator.Companion:Lio/libp2p/mux/yamux/YamuxStreamIdGenerator$Companion;
      16: iload_2
      17: aload_1
      18: invokevirtual #469                // Method io/libp2p/etc/util/netty/mux/MuxId.getId:()J
      21: invokevirtual #475                // Method io/libp2p/mux/yamux/YamuxStreamIdGenerator$Companion.isRemoteSynStreamIdValid:(ZJ)Z
      24: ifne          46
      27: aload_0
      28: invokespecial #477                // Method abruptlyCloseConnection:()V
      31: new           #479                // class io/libp2p/core/Libp2pException
      34: dup
      35: aload_1
      36: iload_2
      37: invokedynamic #484,  0            // InvokeDynamic #3:makeConcatWithConstants:(Lio/libp2p/etc/util/netty/mux/MuxId;Z)Ljava/lang/String;
      42: invokespecial #485                // Method io/libp2p/core/Libp2pException."<init>":(Ljava/lang/String;)V
      45: athrow
      46: return

  public io.netty.channel.ChannelFuture onChildWrite(io.libp2p.etc.util.netty.mux.MuxChannel<io.netty.buffer.ByteBuf>, io.netty.buffer.ByteBuf);
    Code:
       0: aload_1
       1: ldc_w         #491                // String child
       4: invokestatic  #17                 // Method kotlin/jvm/internal/Intrinsics.checkNotNullParameter:(Ljava/lang/Object;Ljava/lang/String;)V
       7: aload_2
       8: ldc_w         #493                // String data
      11: invokestatic  #17                 // Method kotlin/jvm/internal/Intrinsics.checkNotNullParameter:(Ljava/lang/Object;Ljava/lang/String;)V
      14: aload_0
      15: aload_1
      16: invokevirtual #496                // Method io/libp2p/etc/util/netty/mux/MuxChannel.getId:()Lio/libp2p/etc/util/netty/mux/MuxId;
      19: aload_2
      20: invokespecial #97                 // Method getStreamHandlerOrReleaseAndThrow:(Lio/libp2p/etc/util/netty/mux/MuxId;Lio/netty/buffer/ByteBuf;)Lio/libp2p/mux/yamux/YamuxHandler$YamuxStreamHandler;
      23: aload_1
      24: aload_2
      25: invokevirtual #499                // Method io/libp2p/mux/yamux/YamuxHandler$YamuxStreamHandler.sendData:(Lio/libp2p/etc/util/netty/mux/MuxChannel;Lio/netty/buffer/ByteBuf;)Lio/netty/channel/ChannelFuture;
      28: areturn

  public java.lang.Integer pendingChildWriteSize$libp2p(io.netty.buffer.ByteBuf);
    Code:
       0: aload_1
       1: ldc_w         #493                // String data
       4: invokestatic  #17                 // Method kotlin/jvm/internal/Intrinsics.checkNotNullParameter:(Ljava/lang/Object;Ljava/lang/String;)V
       7: aload_1
       8: invokevirtual #284                // Method io/netty/buffer/ByteBuf.readableBytes:()I
      11: invokestatic  #507                // Method java/lang/Integer.valueOf:(I)Ljava/lang/Integer;
      14: areturn

  protected long getMaxPendingChildReadBytes();
    Code:
       0: aload_0
       1: getfield      #42                 // Field initialWindowSize:I
       4: i2l
       5: lreturn

  protected boolean getPauseParentReadsForPausedChild();
    Code:
       0: iconst_0
       1: ireturn

  protected void onChildReadOverflow(io.libp2p.etc.util.netty.mux.MuxChannel<io.netty.buffer.ByteBuf>, java.lang.Throwable);
    Code:
       0: aload_1
       1: ldc_w         #491                // String child
       4: invokestatic  #17                 // Method kotlin/jvm/internal/Intrinsics.checkNotNullParameter:(Ljava/lang/Object;Ljava/lang/String;)V
       7: aload_2
       8: ldc_w         #513                // String cause
      11: invokestatic  #17                 // Method kotlin/jvm/internal/Intrinsics.checkNotNullParameter:(Ljava/lang/Object;Ljava/lang/String;)V
      14: aload_0
      15: getfield      #62                 // Field streamHandlers:Ljava/util/Map;
      18: aload_1
      19: invokevirtual #496                // Method io/libp2p/etc/util/netty/mux/MuxChannel.getId:()Lio/libp2p/etc/util/netty/mux/MuxId;
      22: invokeinterface #103,  2          // InterfaceMethod java/util/Map.get:(Ljava/lang/Object;)Ljava/lang/Object;
      27: checkcast     #105                // class io/libp2p/mux/yamux/YamuxHandler$YamuxStreamHandler
      30: dup
      31: ifnull        41
      34: aload_2
      35: invokevirtual #517                // Method io/libp2p/mux/yamux/YamuxHandler$YamuxStreamHandler.recordReadOverflow:(Ljava/lang/Throwable;)V
      38: goto          42
      41: pop
      42: aload_1
      43: invokestatic  #340                // Method io/libp2p/etc/AttributesKt.getWRITE_FAILURE:()Lio/netty/util/AttributeKey;
      46: invokevirtual #518                // Method io/libp2p/etc/util/netty/mux/MuxChannel.attr:(Lio/netty/util/AttributeKey;)Lio/netty/util/Attribute;
      49: aload_2
      50: invokeinterface #350,  2          // InterfaceMethod io/netty/util/Attribute.set:(Ljava/lang/Object;)V
      55: nop
      56: aload_1
      57: invokevirtual #522                // Method io/libp2p/etc/util/netty/mux/MuxChannel.pipeline:()Lio/netty/channel/ChannelPipeline;
      60: aload_2
      61: invokeinterface #527,  2          // InterfaceMethod io/netty/channel/ChannelPipeline.fireExceptionCaught:(Ljava/lang/Throwable;)Lio/netty/channel/ChannelPipeline;
      66: pop
      67: aload_0
      68: aload_1
      69: aload_2
      70: invokespecial #529                // Method io/libp2p/mux/MuxHandler.onChildReadOverflow:(Lio/libp2p/etc/util/netty/mux/MuxChannel;Ljava/lang/Throwable;)V
      73: goto          85
      76: astore_3
      77: aload_0
      78: aload_1
      79: aload_2
      80: invokespecial #529                // Method io/libp2p/mux/MuxHandler.onChildReadOverflow:(Lio/libp2p/etc/util/netty/mux/MuxChannel;Ljava/lang/Throwable;)V
      83: aload_3
      84: athrow
      85: return
    Exception table:
       from    to  target type
          55    67    76   any
          76    77    76   any

  protected io.netty.buffer.ByteBuf preparePendingChildRead(io.netty.buffer.ByteBuf, long);
    Code:
       0: aload_1
       1: ldc_w         #493                // String data
       4: invokestatic  #17                 // Method kotlin/jvm/internal/Intrinsics.checkNotNullParameter:(Ljava/lang/Object;Ljava/lang/String;)V
       7: nop
       8: aload_1
       9: invokevirtual #284                // Method io/netty/buffer/ByteBuf.readableBytes:()I
      12: istore        4
      14: iload         4
      16: i2l
      17: aload_0
      18: invokevirtual #533                // Method getMaxPendingChildReadBytes:()J
      21: lload_2
      22: lsub
      23: invokestatic  #539                // Method java/lang/Math.max:(JJ)J
      26: ldc2_w        #540                // long 2147483647l
      29: invokestatic  #546                // Method kotlin/ranges/RangesKt.coerceAtMost:(JJ)J
      32: l2i
      33: istore        5
      35: iload         4
      37: iload         5
      39: invokestatic  #552                // Method io/netty/buffer/Unpooled.buffer:(II)Lio/netty/buffer/ByteBuf;
      42: aload_1
      43: invokevirtual #556                // Method io/netty/buffer/ByteBuf.writeBytes:(Lio/netty/buffer/ByteBuf;)Lio/netty/buffer/ByteBuf;
      46: dup
      47: ldc_w         #558                // String buffer(size, maxCapacity).writeBytes(data)
      50: invokestatic  #389                // Method kotlin/jvm/internal/Intrinsics.checkNotNullExpressionValue:(Ljava/lang/Object;Ljava/lang/String;)V
      53: astore        6
      55: aload_1
      56: invokevirtual #560                // Method io/netty/buffer/ByteBuf.release:()Z
      59: pop
      60: aload         6
      62: areturn
      63: astore        4
      65: aload_1
      66: invokevirtual #560                // Method io/netty/buffer/ByteBuf.release:()Z
      69: pop
      70: aload         4
      72: athrow
    Exception table:
       from    to  target type
           7    55    63   any
          63    65    63   any

  protected boolean coalescePendingChildRead(io.netty.buffer.ByteBuf, io.netty.buffer.ByteBuf);
    Code:
       0: aload_1
       1: ldc_w         #567                // String tail
       4: invokestatic  #17                 // Method kotlin/jvm/internal/Intrinsics.checkNotNullParameter:(Ljava/lang/Object;Ljava/lang/String;)V
       7: aload_2
       8: ldc_w         #493                // String data
      11: invokestatic  #17                 // Method kotlin/jvm/internal/Intrinsics.checkNotNullParameter:(Ljava/lang/Object;Ljava/lang/String;)V
      14: aload_2
      15: invokevirtual #284                // Method io/netty/buffer/ByteBuf.readableBytes:()I
      18: aload_1
      19: invokevirtual #570                // Method io/netty/buffer/ByteBuf.maxWritableBytes:()I
      22: if_icmple     27
      25: iconst_0
      26: ireturn
      27: nop
      28: aload_1
      29: aload_2
      30: invokevirtual #556                // Method io/netty/buffer/ByteBuf.writeBytes:(Lio/netty/buffer/ByteBuf;)Lio/netty/buffer/ByteBuf;
      33: pop
      34: aload_2
      35: invokevirtual #560                // Method io/netty/buffer/ByteBuf.release:()Z
      38: pop
      39: goto          50
      42: astore_3
      43: aload_2
      44: invokevirtual #560                // Method io/netty/buffer/ByteBuf.release:()Z
      47: pop
      48: aload_3
      49: athrow
      50: iconst_1
      51: ireturn
    Exception table:
       from    to  target type
          27    34    42   any
          42    43    42   any

  protected void sealPendingChildRead(io.netty.buffer.ByteBuf);
    Code:
       0: aload_1
       1: ldc_w         #493                // String data
       4: invokestatic  #17                 // Method kotlin/jvm/internal/Intrinsics.checkNotNullParameter:(Ljava/lang/Object;Ljava/lang/String;)V
       7: aload_1
       8: invokevirtual #574                // Method io/netty/buffer/ByteBuf.capacity:()I
      11: aload_1
      12: invokevirtual #577                // Method io/netty/buffer/ByteBuf.writerIndex:()I
      15: if_icmple     27
      18: aload_1
      19: aload_1
      20: invokevirtual #577                // Method io/netty/buffer/ByteBuf.writerIndex:()I
      23: invokevirtual #580                // Method io/netty/buffer/ByteBuf.capacity:(I)Lio/netty/buffer/ByteBuf;
      26: pop
      27: return

  protected void onChildReadDelivered(io.libp2p.etc.util.netty.mux.MuxId, int);
    Code:
       0: aload_1
       1: ldc_w         #583                // String id
       4: invokestatic  #17                 // Method kotlin/jvm/internal/Intrinsics.checkNotNullParameter:(Ljava/lang/Object;Ljava/lang/String;)V
       7: aload_0
       8: getfield      #62                 // Field streamHandlers:Ljava/util/Map;
      11: aload_1
      12: invokeinterface #103,  2          // InterfaceMethod java/util/Map.get:(Ljava/lang/Object;)Ljava/lang/Object;
      17: checkcast     #105                // class io/libp2p/mux/yamux/YamuxHandler$YamuxStreamHandler
      20: dup
      21: ifnull        31
      24: iload_2
      25: invokevirtual #587                // Method io/libp2p/mux/yamux/YamuxHandler$YamuxStreamHandler.onBytesDelivered:(I)V
      28: goto          32
      31: pop
      32: return

  public java.lang.Throwable onPendingChildWrite$libp2p(io.libp2p.etc.util.netty.mux.MuxChannel<io.netty.buffer.ByteBuf>, int);
    Code:
       0: aload_1
       1: ldc_w         #491                // String child
       4: invokestatic  #17                 // Method kotlin/jvm/internal/Intrinsics.checkNotNullParameter:(Ljava/lang/Object;Ljava/lang/String;)V
       7: aload_0
       8: getfield      #288                // Field pendingChildWriteBytes:J
      11: iload_2
      12: i2l
      13: ladd
      14: lstore_3
      15: lload_3
      16: aload_0
      17: getfield      #36                 // Field maxBufferedConnectionWrites:I
      20: i2l
      21: lcmp
      22: ifgt          32
      25: aload_0
      26: lload_3
      27: putfield      #288                // Field pendingChildWriteBytes:J
      30: aconst_null
      31: areturn
      32: new           #593                // class io/libp2p/mux/WriteBufferOverflowMuxerException
      35: dup
      36: lload_3
      37: aload_0
      38: getfield      #36                 // Field maxBufferedConnectionWrites:I
      41: aload_1
      42: invokevirtual #496                // Method io/libp2p/etc/util/netty/mux/MuxChannel.getId:()Lio/libp2p/etc/util/netty/mux/MuxId;
      45: invokedynamic #598,  0            // InvokeDynamic #4:makeConcatWithConstants:(JILio/libp2p/etc/util/netty/mux/MuxId;)Ljava/lang/String;
      50: invokespecial #599                // Method io/libp2p/mux/WriteBufferOverflowMuxerException."<init>":(Ljava/lang/String;)V
      53: astore        5
      55: nop
      56: aload_0
      57: getfield      #62                 // Field streamHandlers:Ljava/util/Map;
      60: aload_1
      61: invokevirtual #496                // Method io/libp2p/etc/util/netty/mux/MuxChannel.getId:()Lio/libp2p/etc/util/netty/mux/MuxId;
      64: invokeinterface #103,  2          // InterfaceMethod java/util/Map.get:(Ljava/lang/Object;)Ljava/lang/Object;
      69: checkcast     #105                // class io/libp2p/mux/yamux/YamuxHandler$YamuxStreamHandler
      72: dup
      73: ifnull        87
      76: aload         5
      78: checkcast     #160                // class java/lang/Throwable
      81: invokevirtual #602                // Method io/libp2p/mux/yamux/YamuxHandler$YamuxStreamHandler.onLocalClose:(Ljava/lang/Throwable;)V
      84: goto          88
      87: pop
      88: aload_1
      89: invokevirtual #603                // Method io/libp2p/etc/util/netty/mux/MuxChannel.close:()Lio/netty/channel/ChannelFuture;
      92: pop
      93: goto          133
      96: astore        6
      98: aload         6
     100: aload         5
     102: if_acmpeq     115
     105: aload         5
     107: checkcast     #160                // class java/lang/Throwable
     110: aload         6
     112: invokestatic  #609                // Method kotlin/ExceptionsKt.addSuppressed:(Ljava/lang/Throwable;Ljava/lang/Throwable;)V
     115: aload_1
     116: invokevirtual #603                // Method io/libp2p/etc/util/netty/mux/MuxChannel.close:()Lio/netty/channel/ChannelFuture;
     119: pop
     120: goto          133
     123: astore        6
     125: aload_1
     126: invokevirtual #603                // Method io/libp2p/etc/util/netty/mux/MuxChannel.close:()Lio/netty/channel/ChannelFuture;
     129: pop
     130: aload         6
     132: athrow
     133: aload         5
     135: checkcast     #160                // class java/lang/Throwable
     138: areturn
    Exception table:
       from    to  target type
          55    88    96   Class java/lang/Throwable
          55    88   123   any
          96   115   123   any
         123   125   123   any

  public void onPendingChildWriteComplete$libp2p(io.libp2p.etc.util.netty.mux.MuxChannel<io.netty.buffer.ByteBuf>, io.netty.buffer.ByteBuf, int);
    Code:
       0: aload_1
       1: ldc_w         #491                // String child
       4: invokestatic  #17                 // Method kotlin/jvm/internal/Intrinsics.checkNotNullParameter:(Ljava/lang/Object;Ljava/lang/String;)V
       7: aload_2
       8: ldc_w         #493                // String data
      11: invokestatic  #17                 // Method kotlin/jvm/internal/Intrinsics.checkNotNullParameter:(Ljava/lang/Object;Ljava/lang/String;)V
      14: aload_0
      15: aload_0
      16: getfield      #288                // Field pendingChildWriteBytes:J
      19: iload_3
      20: i2l
      21: lsub
      22: putfield      #288                // Field pendingChildWriteBytes:J
      25: aload_0
      26: getfield      #288                // Field pendingChildWriteBytes:J
      29: lconst_0
      30: lcmp
      31: iflt          38
      34: iconst_1
      35: goto          39
      38: iconst_0
      39: ifne          74
      42: iconst_0
      43: istore        4
      45: aload_0
      46: getfield      #288                // Field pendingChildWriteBytes:J
      49: lneg
      50: aload_1
      51: invokevirtual #496                // Method io/libp2p/etc/util/netty/mux/MuxChannel.getId:()Lio/libp2p/etc/util/netty/mux/MuxId;
      54: invokedynamic #620,  0            // InvokeDynamic #5:makeConcatWithConstants:(JLio/libp2p/etc/util/netty/mux/MuxId;)Ljava/lang/String;
      59: astore        4
      61: new           #305                // class java/lang/IllegalStateException
      64: dup
      65: aload         4
      67: invokevirtual #311                // Method java/lang/Object.toString:()Ljava/lang/String;
      70: invokespecial #312                // Method java/lang/IllegalStateException."<init>":(Ljava/lang/String;)V
      73: athrow
      74: aload_0
      75: getfield      #69                 // Field activeChildWrites:Ljava/util/IdentityHashMap;
      78: aload_2
      79: invokevirtual #623                // Method java/util/IdentityHashMap.remove:(Ljava/lang/Object;)Ljava/lang/Object;
      82: checkcast     #504                // class java/lang/Integer
      85: dup
      86: ifnull        162
      89: astore        5
      91: iconst_0
      92: istore        6
      94: aload_0
      95: aload_0
      96: getfield      #291                // Field activeChildWriteBytes:J
      99: aload         5
     101: invokevirtual #626                // Method java/lang/Integer.intValue:()I
     104: i2l
     105: lsub
     106: putfield      #291                // Field activeChildWriteBytes:J
     109: aload_0
     110: getfield      #291                // Field activeChildWriteBytes:J
     113: lconst_0
     114: lcmp
     115: iflt          122
     118: iconst_1
     119: goto          123
     122: iconst_0
     123: ifne          158
     126: iconst_0
     127: istore        7
     129: aload_0
     130: getfield      #291                // Field activeChildWriteBytes:J
     133: lneg
     134: aload_1
     135: invokevirtual #496                // Method io/libp2p/etc/util/netty/mux/MuxChannel.getId:()Lio/libp2p/etc/util/netty/mux/MuxId;
     138: invokedynamic #629,  0            // InvokeDynamic #6:makeConcatWithConstants:(JLio/libp2p/etc/util/netty/mux/MuxId;)Ljava/lang/String;
     143: astore        7
     145: new           #305                // class java/lang/IllegalStateException
     148: dup
     149: aload         7
     151: invokevirtual #311                // Method java/lang/Object.toString:()Ljava/lang/String;
     154: invokespecial #312                // Method java/lang/IllegalStateException."<init>":(Ljava/lang/String;)V
     157: athrow
     158: nop
     159: goto          164
     162: pop
     163: nop
     164: return

  protected void onLocalOpen(io.libp2p.etc.util.netty.mux.MuxChannel<io.netty.buffer.ByteBuf>);
    Code:
       0: aload_1
       1: ldc_w         #491                // String child
       4: invokestatic  #17                 // Method kotlin/jvm/internal/Intrinsics.checkNotNullParameter:(Ljava/lang/Object;Ljava/lang/String;)V
       7: aload_0
       8: aload_1
       9: invokevirtual #496                // Method io/libp2p/etc/util/netty/mux/MuxChannel.getId:()Lio/libp2p/etc/util/netty/mux/MuxId;
      12: iconst_1
      13: invokespecial #641                // Method verifyAckBacklogLimitNotReached:(Lio/libp2p/etc/util/netty/mux/MuxId;Z)V
      16: aload_0
      17: aload_1
      18: invokevirtual #496                // Method io/libp2p/etc/util/netty/mux/MuxChannel.getId:()Lio/libp2p/etc/util/netty/mux/MuxId;
      21: iconst_1
      22: invokespecial #645                // Method createYamuxStreamHandler:(Lio/libp2p/etc/util/netty/mux/MuxId;Z)Lio/libp2p/mux/yamux/YamuxHandler$YamuxStreamHandler;
      25: invokevirtual #647                // Method io/libp2p/mux/yamux/YamuxHandler$YamuxStreamHandler.onLocalOpen:()V
      28: return

  private final void onRemoteYamuxOpen(io.libp2p.etc.util.netty.mux.MuxId);
    Code:
       0: aload_0
       1: aload_1
       2: iconst_0
       3: invokespecial #641                // Method verifyAckBacklogLimitNotReached:(Lio/libp2p/etc/util/netty/mux/MuxId;Z)V
       6: aload_0
       7: aload_1
       8: iconst_0
       9: invokespecial #645                // Method createYamuxStreamHandler:(Lio/libp2p/etc/util/netty/mux/MuxId;Z)Lio/libp2p/mux/yamux/YamuxHandler$YamuxStreamHandler;
      12: invokevirtual #650                // Method io/libp2p/mux/yamux/YamuxHandler$YamuxStreamHandler.onRemoteOpen:()V
      15: aload_0
      16: aload_1
      17: invokevirtual #652                // Method onRemoteOpen:(Lio/libp2p/etc/util/netty/mux/MuxId;)V
      20: return

  private final void verifyAckBacklogLimitNotReached(io.libp2p.etc.util.netty.mux.MuxId, boolean);
    Code:
       0: aload_0
       1: getfield      #62                 // Field streamHandlers:Ljava/util/Map;
       4: invokeinterface #128,  1          // InterfaceMethod java/util/Map.values:()Ljava/util/Collection;
       9: checkcast     #130                // class java/lang/Iterable
      12: astore        4
      14: iconst_0
      15: istore        5
      17: aload         4
      19: instanceof    #654                // class java/util/Collection
      22: ifeq          42
      25: aload         4
      27: checkcast     #654                // class java/util/Collection
      30: invokeinterface #657,  1          // InterfaceMethod java/util/Collection.isEmpty:()Z
      35: ifeq          42
      38: iconst_0
      39: goto          127
      42: iconst_0
      43: istore        6
      45: aload         4
      47: invokeinterface #134,  1          // InterfaceMethod java/lang/Iterable.iterator:()Ljava/util/Iterator;
      52: astore        7
      54: aload         7
      56: invokeinterface #140,  1          // InterfaceMethod java/util/Iterator.hasNext:()Z
      61: ifeq          125
      64: aload         7
      66: invokeinterface #144,  1          // InterfaceMethod java/util/Iterator.next:()Ljava/lang/Object;
      71: astore        8
      73: aload         8
      75: checkcast     #105                // class io/libp2p/mux/yamux/YamuxHandler$YamuxStreamHandler
      78: astore        9
      80: iconst_0
      81: istore        10
      83: aload         9
      85: invokevirtual #660                // Method io/libp2p/mux/yamux/YamuxHandler$YamuxStreamHandler.getOutbound:()Z
      88: iload_2
      89: if_icmpne     107
      92: aload         9
      94: invokevirtual #664                // Method io/libp2p/mux/yamux/YamuxHandler$YamuxStreamHandler.getAcknowledged:()Ljava/util/concurrent/atomic/AtomicBoolean;
      97: invokevirtual #668                // Method java/util/concurrent/atomic/AtomicBoolean.get:()Z
     100: ifne          107
     103: iconst_1
     104: goto          108
     107: iconst_0
     108: ifeq          54
     111: iinc          6, 1
     114: iload         6
     116: ifge          54
     119: invokestatic  #673                // Method kotlin/collections/CollectionsKt.throwCountOverflow:()V
     122: goto          54
     125: iload         6
     127: istore_3
     128: iload_3
     129: aload_0
     130: getfield      #39                 // Field ackBacklogLimit:I
     133: if_icmplt     154
     136: new           #675                // class io/libp2p/mux/AckBacklogLimitExceededMuxerException
     139: dup
     140: aload_0
     141: getfield      #39                 // Field ackBacklogLimit:I
     144: aload_1
     145: invokedynamic #680,  0            // InvokeDynamic #7:makeConcatWithConstants:(ILio/libp2p/etc/util/netty/mux/MuxId;)Ljava/lang/String;
     150: invokespecial #681                // Method io/libp2p/mux/AckBacklogLimitExceededMuxerException."<init>":(Ljava/lang/String;)V
     153: athrow
     154: return

  private final io.libp2p.mux.yamux.YamuxHandler$YamuxStreamHandler createYamuxStreamHandler(io.libp2p.etc.util.netty.mux.MuxId, boolean);
    Code:
       0: new           #105                // class io/libp2p/mux/yamux/YamuxHandler$YamuxStreamHandler
       3: dup
       4: aload_0
       5: aload_1
       6: iload_2
       7: invokespecial #690                // Method io/libp2p/mux/yamux/YamuxHandler$YamuxStreamHandler."<init>":(Lio/libp2p/mux/yamux/YamuxHandler;Lio/libp2p/etc/util/netty/mux/MuxId;Z)V
      10: astore_3
      11: aload_0
      12: getfield      #62                 // Field streamHandlers:Ljava/util/Map;
      15: aload_1
      16: aload_3
      17: invokeinterface #694,  3          // InterfaceMethod java/util/Map.put:(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
      22: pop
      23: aload_3
      24: areturn

  protected void onLocalDisconnect(io.libp2p.etc.util.netty.mux.MuxChannel<io.netty.buffer.ByteBuf>);
    Code:
       0: aload_1
       1: ldc_w         #491                // String child
       4: invokestatic  #17                 // Method kotlin/jvm/internal/Intrinsics.checkNotNullParameter:(Ljava/lang/Object;Ljava/lang/String;)V
       7: aload_0
       8: aload_1
       9: invokevirtual #496                // Method io/libp2p/etc/util/netty/mux/MuxChannel.getId:()Lio/libp2p/etc/util/netty/mux/MuxId;
      12: invokespecial #698                // Method getStreamHandlerOrThrow:(Lio/libp2p/etc/util/netty/mux/MuxId;)Lio/libp2p/mux/yamux/YamuxHandler$YamuxStreamHandler;
      15: invokevirtual #700                // Method io/libp2p/mux/yamux/YamuxHandler$YamuxStreamHandler.onLocalDisconnect:()V
      18: return

  protected void onLocalClose(io.libp2p.etc.util.netty.mux.MuxChannel<io.netty.buffer.ByteBuf>);
    Code:
       0: aload_1
       1: ldc_w         #491                // String child
       4: invokestatic  #17                 // Method kotlin/jvm/internal/Intrinsics.checkNotNullParameter:(Ljava/lang/Object;Ljava/lang/String;)V
       7: aload_0
       8: getfield      #62                 // Field streamHandlers:Ljava/util/Map;
      11: aload_1
      12: invokevirtual #496                // Method io/libp2p/etc/util/netty/mux/MuxChannel.getId:()Lio/libp2p/etc/util/netty/mux/MuxId;
      15: invokeinterface #701,  2          // InterfaceMethod java/util/Map.remove:(Ljava/lang/Object;)Ljava/lang/Object;
      20: checkcast     #105                // class io/libp2p/mux/yamux/YamuxHandler$YamuxStreamHandler
      23: dup
      24: ifnull        36
      27: aconst_null
      28: iconst_1
      29: aconst_null
      30: invokestatic  #704                // Method io/libp2p/mux/yamux/YamuxHandler$YamuxStreamHandler.onLocalClose$default:(Lio/libp2p/mux/yamux/YamuxHandler$YamuxStreamHandler;Ljava/lang/Throwable;ILjava/lang/Object;)V
      33: goto          37
      36: pop
      37: return

  protected void onChildClosed(io.libp2p.etc.util.netty.mux.MuxChannel<io.netty.buffer.ByteBuf>);
    Code:
       0: aload_1
       1: ldc_w         #491                // String child
       4: invokestatic  #17                 // Method kotlin/jvm/internal/Intrinsics.checkNotNullParameter:(Ljava/lang/Object;Ljava/lang/String;)V
       7: aload_0
       8: getfield      #62                 // Field streamHandlers:Ljava/util/Map;
      11: aload_1
      12: invokevirtual #496                // Method io/libp2p/etc/util/netty/mux/MuxChannel.getId:()Lio/libp2p/etc/util/netty/mux/MuxId;
      15: invokeinterface #701,  2          // InterfaceMethod java/util/Map.remove:(Ljava/lang/Object;)Ljava/lang/Object;
      20: checkcast     #105                // class io/libp2p/mux/yamux/YamuxHandler$YamuxStreamHandler
      23: dup
      24: ifnull        36
      27: aconst_null
      28: iconst_1
      29: aconst_null
      30: invokestatic  #148                // Method io/libp2p/mux/yamux/YamuxHandler$YamuxStreamHandler.dispose$default:(Lio/libp2p/mux/yamux/YamuxHandler$YamuxStreamHandler;Ljava/lang/Throwable;ILjava/lang/Object;)V
      33: goto          37
      36: pop
      37: return

  private final void handlePing(io.libp2p.mux.yamux.YamuxFrame);
    Code:
       0: aload_1
       1: invokevirtual #225                // Method io/libp2p/mux/yamux/YamuxFrame.getId:()Lio/libp2p/etc/util/netty/mux/MuxId;
       4: invokevirtual #469                // Method io/libp2p/etc/util/netty/mux/MuxId.getId:()J
       7: getstatic     #710                // Field io/libp2p/mux/yamux/YamuxId.Companion:Lio/libp2p/mux/yamux/YamuxId$Companion;
      10: invokevirtual #715                // Method io/libp2p/mux/yamux/YamuxId$Companion.getSESSION_STREAM_ID:()J
      13: lcmp
      14: ifeq          34
      17: new           #717                // class io/libp2p/mux/InvalidFrameMuxerException
      20: dup
      21: aload_1
      22: invokevirtual #225                // Method io/libp2p/mux/yamux/YamuxFrame.getId:()Lio/libp2p/etc/util/netty/mux/MuxId;
      25: invokedynamic #722,  0            // InvokeDynamic #8:makeConcatWithConstants:(Lio/libp2p/etc/util/netty/mux/MuxId;)Ljava/lang/String;
      30: invokespecial #723                // Method io/libp2p/mux/InvalidFrameMuxerException."<init>":(Ljava/lang/String;)V
      33: athrow
      34: aload_1
      35: invokevirtual #209                // Method io/libp2p/mux/yamux/YamuxFrame.getFlags:()Ljava/util/Set;
      38: getstatic     #215                // Field io/libp2p/mux/yamux/YamuxFlag.SYN:Lio/libp2p/mux/yamux/YamuxFlag;
      41: invokeinterface #221,  2          // InterfaceMethod java/util/Set.contains:(Ljava/lang/Object;)Z
      46: ifeq          94
      49: aload_0
      50: new           #183                // class io/libp2p/mux/yamux/YamuxFrame
      53: dup
      54: getstatic     #710                // Field io/libp2p/mux/yamux/YamuxId.Companion:Lio/libp2p/mux/yamux/YamuxId$Companion;
      57: aload_1
      58: invokevirtual #225                // Method io/libp2p/mux/yamux/YamuxFrame.getId:()Lio/libp2p/etc/util/netty/mux/MuxId;
      61: invokevirtual #727                // Method io/libp2p/etc/util/netty/mux/MuxId.getParentId:()Lio/netty/channel/ChannelId;
      64: invokevirtual #731                // Method io/libp2p/mux/yamux/YamuxId$Companion.sessionId:(Lio/netty/channel/ChannelId;)Lio/libp2p/mux/yamux/YamuxId;
      67: checkcast     #120                // class io/libp2p/etc/util/netty/mux/MuxId
      70: getstatic     #735                // Field io/libp2p/mux/yamux/YamuxType.PING:Lio/libp2p/mux/yamux/YamuxType;
      73: getstatic     #738                // Field io/libp2p/mux/yamux/YamuxFlag.ACK:Lio/libp2p/mux/yamux/YamuxFlag;
      76: invokevirtual #741                // Method io/libp2p/mux/yamux/YamuxFlag.getAsSet:()Ljava/util/Set;
      79: aload_1
      80: invokevirtual #744                // Method io/libp2p/mux/yamux/YamuxFrame.getLength:()J
      83: aconst_null
      84: bipush        16
      86: aconst_null
      87: invokespecial #747                // Method io/libp2p/mux/yamux/YamuxFrame."<init>":(Lio/libp2p/etc/util/netty/mux/MuxId;Lio/libp2p/mux/yamux/YamuxType;Ljava/util/Set;JLio/netty/buffer/ByteBuf;ILkotlin/jvm/internal/DefaultConstructorMarker;)V
      90: invokespecial #749                // Method writeAndFlushFrame:(Lio/libp2p/mux/yamux/YamuxFrame;)Lio/netty/channel/ChannelFuture;
      93: pop
      94: return

  private final void handleGoAway(io.libp2p.mux.yamux.YamuxFrame);
    Code:
       0: aload_1
       1: invokevirtual #225                // Method io/libp2p/mux/yamux/YamuxFrame.getId:()Lio/libp2p/etc/util/netty/mux/MuxId;
       4: invokevirtual #469                // Method io/libp2p/etc/util/netty/mux/MuxId.getId:()J
       7: getstatic     #710                // Field io/libp2p/mux/yamux/YamuxId.Companion:Lio/libp2p/mux/yamux/YamuxId$Companion;
      10: invokevirtual #715                // Method io/libp2p/mux/yamux/YamuxId$Companion.getSESSION_STREAM_ID:()J
      13: lcmp
      14: ifeq          34
      17: new           #717                // class io/libp2p/mux/InvalidFrameMuxerException
      20: dup
      21: aload_1
      22: invokevirtual #225                // Method io/libp2p/mux/yamux/YamuxFrame.getId:()Lio/libp2p/etc/util/netty/mux/MuxId;
      25: invokedynamic #752,  0            // InvokeDynamic #9:makeConcatWithConstants:(Lio/libp2p/etc/util/netty/mux/MuxId;)Ljava/lang/String;
      30: invokespecial #723                // Method io/libp2p/mux/InvalidFrameMuxerException."<init>":(Ljava/lang/String;)V
      33: athrow
      34: aload_0
      35: getfield      #76                 // Field goAwayPromise:Ljava/util/concurrent/CompletableFuture;
      38: aload_1
      39: invokevirtual #744                // Method io/libp2p/mux/yamux/YamuxFrame.getLength:()J
      42: invokestatic  #417                // Method java/lang/Long.valueOf:(J)Ljava/lang/Long;
      45: invokevirtual #755                // Method java/util/concurrent/CompletableFuture.complete:(Ljava/lang/Object;)Z
      48: pop
      49: return

  private final int calculateTotalBufferedWrites();
    Code:
       0: aload_0
       1: getfield      #62                 // Field streamHandlers:Ljava/util/Map;
       4: invokeinterface #128,  1          // InterfaceMethod java/util/Map.values:()Ljava/util/Collection;
       9: checkcast     #130                // class java/lang/Iterable
      12: astore_1
      13: iconst_0
      14: istore_2
      15: aload_1
      16: invokeinterface #134,  1          // InterfaceMethod java/lang/Iterable.iterator:()Ljava/util/Iterator;
      21: astore_3
      22: aload_3
      23: invokeinterface #140,  1          // InterfaceMethod java/util/Iterator.hasNext:()Z
      28: ifeq          71
      31: aload_3
      32: invokeinterface #144,  1          // InterfaceMethod java/util/Iterator.next:()Ljava/lang/Object;
      37: astore        4
      39: iload_2
      40: aload         4
      42: checkcast     #105                // class io/libp2p/mux/yamux/YamuxHandler$YamuxStreamHandler
      45: astore        5
      47: istore        7
      49: iconst_0
      50: istore        6
      52: aload         5
      54: invokevirtual #759                // Method io/libp2p/mux/yamux/YamuxHandler$YamuxStreamHandler.getSendBuffer:()Lio/libp2p/etc/util/netty/ByteBufQueue;
      57: invokevirtual #762                // Method io/libp2p/etc/util/netty/ByteBufQueue.readableBytes:()I
      60: istore        8
      62: iload         7
      64: iload         8
      66: iadd
      67: istore_2
      68: goto          22
      71: iload_2
      72: ireturn

  protected io.libp2p.mux.yamux.YamuxId generateNextId();
    Code:
       0: new           #707                // class io/libp2p/mux/yamux/YamuxId
       3: dup
       4: aload_0
       5: invokevirtual #255                // Method getChannelHandlerContext:()Lio/netty/channel/ChannelHandlerContext;
       8: invokeinterface #261,  1          // InterfaceMethod io/netty/channel/ChannelHandlerContext.channel:()Lio/netty/channel/Channel;
      13: invokeinterface #767,  1          // InterfaceMethod io/netty/channel/Channel.id:()Lio/netty/channel/ChannelId;
      18: dup
      19: ldc_w         #769                // String getChannelHandlerContext().channel().id()
      22: invokestatic  #389                // Method kotlin/jvm/internal/Intrinsics.checkNotNullExpressionValue:(Ljava/lang/Object;Ljava/lang/String;)V
      25: aload_0
      26: getfield      #51                 // Field idGenerator:Lio/libp2p/mux/yamux/YamuxStreamIdGenerator;
      29: invokevirtual #771                // Method io/libp2p/mux/yamux/YamuxStreamIdGenerator.next:()J
      32: invokespecial #774                // Method io/libp2p/mux/yamux/YamuxId."<init>":(Lio/netty/channel/ChannelId;J)V
      35: areturn

  private static final void writeAndFlushFrame$lambda-3(io.netty.channel.ChannelHandlerContext);
    Code:
       0: aload_0
       1: ldc_w         #776                // String $ctx
       4: invokestatic  #17                 // Method kotlin/jvm/internal/Intrinsics.checkNotNullParameter:(Ljava/lang/Object;Ljava/lang/String;)V
       7: aload_0
       8: invokeinterface #463,  1          // InterfaceMethod io/netty/channel/ChannelHandlerContext.close:()Lio/netty/channel/ChannelFuture;
      13: pop
      14: return

  public io.netty.channel.ChannelFuture onChildWrite(io.libp2p.etc.util.netty.mux.MuxChannel, java.lang.Object);
    Code:
       0: aload_0
       1: aload_1
       2: aload_2
       3: checkcast     #122                // class io/netty/buffer/ByteBuf
       6: invokevirtual #779                // Method onChildWrite:(Lio/libp2p/etc/util/netty/mux/MuxChannel;Lio/netty/buffer/ByteBuf;)Lio/netty/channel/ChannelFuture;
       9: areturn

  public java.lang.Integer pendingChildWriteSize$libp2p(java.lang.Object);
    Code:
       0: aload_0
       1: aload_1
       2: checkcast     #122                // class io/netty/buffer/ByteBuf
       5: invokevirtual #782                // Method pendingChildWriteSize$libp2p:(Lio/netty/buffer/ByteBuf;)Ljava/lang/Integer;
       8: areturn

  public java.lang.Object preparePendingChildRead(java.lang.Object, long);
    Code:
       0: aload_0
       1: aload_1
       2: checkcast     #122                // class io/netty/buffer/ByteBuf
       5: lload_2
       6: invokevirtual #785                // Method preparePendingChildRead:(Lio/netty/buffer/ByteBuf;J)Lio/netty/buffer/ByteBuf;
       9: areturn

  public boolean coalescePendingChildRead(java.lang.Object, java.lang.Object);
    Code:
       0: aload_0
       1: aload_1
       2: checkcast     #122                // class io/netty/buffer/ByteBuf
       5: aload_2
       6: checkcast     #122                // class io/netty/buffer/ByteBuf
       9: invokevirtual #788                // Method coalescePendingChildRead:(Lio/netty/buffer/ByteBuf;Lio/netty/buffer/ByteBuf;)Z
      12: ireturn

  public void sealPendingChildRead(java.lang.Object);
    Code:
       0: aload_0
       1: aload_1
       2: checkcast     #122                // class io/netty/buffer/ByteBuf
       5: invokevirtual #790                // Method sealPendingChildRead:(Lio/netty/buffer/ByteBuf;)V
       8: return

  public void onPendingChildWriteComplete$libp2p(io.libp2p.etc.util.netty.mux.MuxChannel, java.lang.Object, int);
    Code:
       0: aload_0
       1: aload_1
       2: aload_2
       3: checkcast     #122                // class io/netty/buffer/ByteBuf
       6: iload_3
       7: invokevirtual #793                // Method onPendingChildWriteComplete$libp2p:(Lio/libp2p/etc/util/netty/mux/MuxChannel;Lio/netty/buffer/ByteBuf;I)V
      10: return

  public io.libp2p.etc.util.netty.mux.MuxId generateNextId();
    Code:
       0: aload_0
       1: invokevirtual #795                // Method generateNextId:()Lio/libp2p/mux/yamux/YamuxId;
       4: checkcast     #120                // class io/libp2p/etc/util/netty/mux/MuxId
       7: areturn

  public static final int access$getInitialWindowSize$p(io.libp2p.mux.yamux.YamuxHandler);
    Code:
       0: aload_0
       1: getfield      #42                 // Field initialWindowSize:I
       4: ireturn

  public static final void access$childRead(io.libp2p.mux.yamux.YamuxHandler, io.libp2p.etc.util.netty.mux.MuxId, io.netty.buffer.ByteBuf);
    Code:
       0: aload_0
       1: aload_1
       2: aload_2
       3: invokevirtual #804                // Method childRead:(Lio/libp2p/etc/util/netty/mux/MuxId;Ljava/lang/Object;)V
       6: return

  public static final io.netty.channel.ChannelFuture access$writeAndFlushFrame(io.libp2p.mux.yamux.YamuxHandler, io.libp2p.mux.yamux.YamuxFrame);
    Code:
       0: aload_0
       1: aload_1
       2: invokespecial #749                // Method writeAndFlushFrame:(Lio/libp2p/mux/yamux/YamuxFrame;)Lio/netty/channel/ChannelFuture;
       5: areturn

  public static final void access$onRemoteDisconnect(io.libp2p.mux.yamux.YamuxHandler, io.libp2p.etc.util.netty.mux.MuxId);
    Code:
       0: aload_0
       1: aload_1
       2: invokevirtual #811                // Method onRemoteDisconnect:(Lio/libp2p/etc/util/netty/mux/MuxId;)V
       5: return

  public static final void access$onRemoteClose(io.libp2p.mux.yamux.YamuxHandler, io.libp2p.etc.util.netty.mux.MuxId);
    Code:
       0: aload_0
       1: aload_1
       2: invokevirtual #815                // Method onRemoteClose:(Lio/libp2p/etc/util/netty/mux/MuxId;)V
       5: return

  public static final int access$calculateTotalBufferedWrites(io.libp2p.mux.yamux.YamuxHandler);
    Code:
       0: aload_0
       1: invokespecial #315                // Method calculateTotalBufferedWrites:()I
       4: ireturn

  public static final int access$getMaxBufferedConnectionWrites$p(io.libp2p.mux.yamux.YamuxHandler);
    Code:
       0: aload_0
       1: getfield      #36                 // Field maxBufferedConnectionWrites:I
       4: ireturn

  public static final java.util.IdentityHashMap access$getActiveChildWrites$p(io.libp2p.mux.yamux.YamuxHandler);
    Code:
       0: aload_0
       1: getfield      #69                 // Field activeChildWrites:Ljava/util/IdentityHashMap;
       4: areturn

  public static final void access$setActiveChildWriteBytes$p(io.libp2p.mux.yamux.YamuxHandler, long);
    Code:
       0: aload_0
       1: lload_1
       2: putfield      #291                // Field activeChildWriteBytes:J
       5: return

  public static final long access$getActiveChildWriteBytes$p(io.libp2p.mux.yamux.YamuxHandler);
    Code:
       0: aload_0
       1: getfield      #291                // Field activeChildWriteBytes:J
       4: lreturn
}

```
