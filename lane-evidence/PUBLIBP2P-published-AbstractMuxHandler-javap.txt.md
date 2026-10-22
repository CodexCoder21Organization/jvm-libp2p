```text
Compiled from "AbstractMuxHandler.kt"
public abstract class io.libp2p.etc.util.netty.mux.AbstractMuxHandler<TData> extends io.netty.channel.ChannelInboundHandlerAdapter {
  private final int maxInboundStreams;

  private final java.util.Map<io.libp2p.etc.util.netty.mux.MuxId, io.libp2p.etc.util.netty.mux.MuxChannel<TData>> streamMap;

  private io.netty.channel.ChannelHandlerContext ctx;

  private final java.util.concurrent.CompletableFuture<java.lang.Void> activeFuture;

  private final java.util.concurrent.ConcurrentHashMap$KeySetView<java.util.concurrent.CompletableFuture<io.libp2p.etc.util.netty.mux.MuxChannel<TData>>, java.lang.Boolean> pendingStreams;

  private volatile boolean closed;

  private final java.util.Set<io.libp2p.etc.util.netty.mux.MuxId> pendingReadComplete;

  private final java.util.Map<io.libp2p.etc.util.netty.mux.MuxId, io.libp2p.etc.util.netty.mux.AbstractMuxHandler$PendingInbound<TData>> pendingInbound;

  private final java.util.Set<io.libp2p.etc.util.netty.mux.MuxId> pausedChildren;

  private java.lang.Boolean parentAutoReadBeforePause;

  private final java.lang.Object parentReadLock;

  private final boolean pauseParentReadsForPausedChild;

  private final long maxPendingChildReadBytes;

  private final java.lang.Integer maxPendingChildReadFrames;

  private int openInboundStreams;

  private long rejectedInboundStreams;

  public io.libp2p.etc.util.netty.mux.AbstractMuxHandler(int);
    Code:
       0: aload_0
       1: invokespecial #10                 // Method io/netty/channel/ChannelInboundHandlerAdapter."<init>":()V
       4: aload_0
       5: iload_1
       6: putfield      #14                 // Field maxInboundStreams:I
       9: aload_0
      10: new           #16                 // class java/util/LinkedHashMap
      13: dup
      14: invokespecial #17                 // Method java/util/LinkedHashMap."<init>":()V
      17: checkcast     #19                 // class java/util/Map
      20: putfield      #23                 // Field streamMap:Ljava/util/Map;
      23: aload_0
      24: new           #25                 // class java/util/concurrent/CompletableFuture
      27: dup
      28: invokespecial #26                 // Method java/util/concurrent/CompletableFuture."<init>":()V
      31: putfield      #30                 // Field activeFuture:Ljava/util/concurrent/CompletableFuture;
      34: aload_0
      35: invokestatic  #36                 // Method java/util/concurrent/ConcurrentHashMap.newKeySet:()Ljava/util/concurrent/ConcurrentHashMap$KeySetView;
      38: putfield      #40                 // Field pendingStreams:Ljava/util/concurrent/ConcurrentHashMap$KeySetView;
      41: aload_0
      42: new           #42                 // class java/util/LinkedHashSet
      45: dup
      46: invokespecial #43                 // Method java/util/LinkedHashSet."<init>":()V
      49: checkcast     #45                 // class java/util/Set
      52: putfield      #49                 // Field pendingReadComplete:Ljava/util/Set;
      55: aload_0
      56: new           #16                 // class java/util/LinkedHashMap
      59: dup
      60: invokespecial #17                 // Method java/util/LinkedHashMap."<init>":()V
      63: checkcast     #19                 // class java/util/Map
      66: putfield      #52                 // Field pendingInbound:Ljava/util/Map;
      69: aload_0
      70: new           #42                 // class java/util/LinkedHashSet
      73: dup
      74: invokespecial #43                 // Method java/util/LinkedHashSet."<init>":()V
      77: checkcast     #45                 // class java/util/Set
      80: putfield      #55                 // Field pausedChildren:Ljava/util/Set;
      83: aload_0
      84: new           #57                 // class java/lang/Object
      87: dup
      88: invokespecial #58                 // Method java/lang/Object."<init>":()V
      91: putfield      #62                 // Field parentReadLock:Ljava/lang/Object;
      94: aload_0
      95: iconst_1
      96: putfield      #66                 // Field pauseParentReadsForPausedChild:Z
      99: aload_0
     100: ldc2_w        #67                 // long 4194304l
     103: putfield      #72                 // Field maxPendingChildReadBytes:J
     106: aload_0
     107: bipush        64
     109: invokestatic  #78                 // Method java/lang/Integer.valueOf:(I)Ljava/lang/Integer;
     112: putfield      #82                 // Field maxPendingChildReadFrames:Ljava/lang/Integer;
     115: return

  public io.libp2p.etc.util.netty.mux.AbstractMuxHandler(int, int, kotlin.jvm.internal.DefaultConstructorMarker);
    Code:
       0: iload_2
       1: iconst_1
       2: iand
       3: ifeq          10
       6: sipush        512
       9: istore_1
      10: aload_0
      11: iload_1
      12: invokespecial #87                 // Method "<init>":(I)V
      15: return

  public final io.netty.channel.ChannelHandlerContext getCtx();
    Code:
       0: aload_0
       1: getfield      #94                 // Field ctx:Lio/netty/channel/ChannelHandlerContext;
       4: areturn

  public final void setCtx(io.netty.channel.ChannelHandlerContext);
    Code:
       0: aload_0
       1: aload_1
       2: putfield      #94                 // Field ctx:Lio/netty/channel/ChannelHandlerContext;
       5: return

  protected abstract kotlin.jvm.functions.Function1<io.libp2p.etc.util.netty.mux.MuxChannel<TData>, kotlin.Unit> getInboundInitializer();

  protected boolean getPauseParentReadsForPausedChild();
    Code:
       0: aload_0
       1: getfield      #66                 // Field pauseParentReadsForPausedChild:Z
       4: ireturn

  protected long getMaxPendingChildReadBytes();
    Code:
       0: aload_0
       1: getfield      #72                 // Field maxPendingChildReadBytes:J
       4: lreturn

  protected java.lang.Integer getMaxPendingChildReadFrames();
    Code:
       0: aload_0
       1: getfield      #82                 // Field maxPendingChildReadFrames:Ljava/lang/Integer;
       4: areturn

  protected int pendingChildReadSize(TData);
    Code:
       0: iconst_1
       1: ireturn

  protected TData preparePendingChildRead(TData, long);
    Code:
       0: aload_1
       1: areturn

  protected boolean coalescePendingChildRead(TData, TData);
    Code:
       0: iconst_0
       1: ireturn

  protected void sealPendingChildRead(TData);
    Code:
       0: return

  protected void onChildReadDelivered(io.libp2p.etc.util.netty.mux.MuxId, int);
    Code:
       0: aload_1
       1: ldc           #126                // String id
       3: invokestatic  #132                // Method kotlin/jvm/internal/Intrinsics.checkNotNullParameter:(Ljava/lang/Object;Ljava/lang/String;)V
       6: return

  public final int openInboundStreamCount();
    Code:
       0: aload_0
       1: getfield      #139                // Field openInboundStreams:I
       4: ireturn

  public final long rejectedInboundStreamCount();
    Code:
       0: aload_0
       1: getfield      #143                // Field rejectedInboundStreams:J
       4: lreturn

  public void handlerAdded(io.netty.channel.ChannelHandlerContext);
    Code:
       0: aload_1
       1: ldc           #145                // String ctx
       3: invokestatic  #132                // Method kotlin/jvm/internal/Intrinsics.checkNotNullParameter:(Ljava/lang/Object;Ljava/lang/String;)V
       6: aload_0
       7: aload_1
       8: invokespecial #147                // Method io/netty/channel/ChannelInboundHandlerAdapter.handlerAdded:(Lio/netty/channel/ChannelHandlerContext;)V
      11: aload_0
      12: aload_1
      13: putfield      #94                 // Field ctx:Lio/netty/channel/ChannelHandlerContext;
      16: aload_1
      17: invokeinterface #153,  1          // InterfaceMethod io/netty/channel/ChannelHandlerContext.channel:()Lio/netty/channel/Channel;
      22: invokeinterface #159,  1          // InterfaceMethod io/netty/channel/Channel.closeFuture:()Lio/netty/channel/ChannelFuture;
      27: aload_0
      28: invokedynamic #177,  0            // InvokeDynamic #0:operationComplete:(Lio/libp2p/etc/util/netty/mux/AbstractMuxHandler;)Lio/netty/util/concurrent/GenericFutureListener;
      33: invokeinterface #183,  2          // InterfaceMethod io/netty/channel/ChannelFuture.addListener:(Lio/netty/util/concurrent/GenericFutureListener;)Lio/netty/channel/ChannelFuture;
      38: pop
      39: return

  public void channelActive(io.netty.channel.ChannelHandlerContext);
    Code:
       0: aload_0
       1: getfield      #187                // Field closed:Z
       4: ifne          42
       7: aload_1
       8: dup
       9: ifnull        37
      12: invokeinterface #153,  1          // InterfaceMethod io/netty/channel/ChannelHandlerContext.channel:()Lio/netty/channel/Channel;
      17: dup
      18: ifnull        37
      21: invokeinterface #190,  1          // InterfaceMethod io/netty/channel/Channel.isOpen:()Z
      26: ifne          33
      29: iconst_1
      30: goto          39
      33: iconst_0
      34: goto          39
      37: pop
      38: iconst_0
      39: ifeq          61
      42: aload_0
      43: getfield      #30                 // Field activeFuture:Ljava/util/concurrent/CompletableFuture;
      46: new           #192                // class io/libp2p/core/ConnectionClosedException
      49: dup
      50: invokespecial #193                // Method io/libp2p/core/ConnectionClosedException."<init>":()V
      53: checkcast     #195                // class java/lang/Throwable
      56: invokevirtual #199                // Method java/util/concurrent/CompletableFuture.completeExceptionally:(Ljava/lang/Throwable;)Z
      59: pop
      60: return
      61: aload_0
      62: getfield      #30                 // Field activeFuture:Ljava/util/concurrent/CompletableFuture;
      65: aconst_null
      66: invokevirtual #203                // Method java/util/concurrent/CompletableFuture.complete:(Ljava/lang/Object;)Z
      69: pop
      70: aload_0
      71: aload_1
      72: invokespecial #205                // Method io/netty/channel/ChannelInboundHandlerAdapter.channelActive:(Lio/netty/channel/ChannelHandlerContext;)V
      75: return

  public void channelUnregistered(io.netty.channel.ChannelHandlerContext);
    Code:
       0: aload_0
       1: getfield      #30                 // Field activeFuture:Ljava/util/concurrent/CompletableFuture;
       4: new           #192                // class io/libp2p/core/ConnectionClosedException
       7: dup
       8: invokespecial #193                // Method io/libp2p/core/ConnectionClosedException."<init>":()V
      11: checkcast     #195                // class java/lang/Throwable
      14: invokevirtual #199                // Method java/util/concurrent/CompletableFuture.completeExceptionally:(Ljava/lang/Throwable;)Z
      17: pop
      18: aload_0
      19: iconst_1
      20: putfield      #187                // Field closed:Z
      23: aload_0
      24: getfield      #52                 // Field pendingInbound:Ljava/util/Map;
      27: invokeinterface #210,  1          // InterfaceMethod java/util/Map.values:()Ljava/util/Collection;
      32: checkcast     #212                // class java/lang/Iterable
      35: invokestatic  #218                // Method kotlin/collections/CollectionsKt.toList:(Ljava/lang/Iterable;)Ljava/util/List;
      38: astore_2
      39: aload_0
      40: getfield      #52                 // Field pendingInbound:Ljava/util/Map;
      43: invokeinterface #221,  1          // InterfaceMethod java/util/Map.clear:()V
      48: aload_2
      49: checkcast     #212                // class java/lang/Iterable
      52: astore_3
      53: iconst_0
      54: istore        4
      56: aload_3
      57: invokeinterface #225,  1          // InterfaceMethod java/lang/Iterable.iterator:()Ljava/util/Iterator;
      62: astore        5
      64: aload         5
      66: invokeinterface #230,  1          // InterfaceMethod java/util/Iterator.hasNext:()Z
      71: ifeq          155
      74: aload         5
      76: invokeinterface #234,  1          // InterfaceMethod java/util/Iterator.next:()Ljava/lang/Object;
      81: astore        6
      83: aload         6
      85: checkcast     #236                // class io/libp2p/etc/util/netty/mux/AbstractMuxHandler$PendingInbound
      88: astore        7
      90: iconst_0
      91: istore        8
      93: aload         7
      95: invokevirtual #240                // Method io/libp2p/etc/util/netty/mux/AbstractMuxHandler$PendingInbound.getMessages:()Ljava/util/ArrayDeque;
      98: checkcast     #212                // class java/lang/Iterable
     101: astore        9
     103: iconst_0
     104: istore        10
     106: aload         9
     108: invokeinterface #225,  1          // InterfaceMethod java/lang/Iterable.iterator:()Ljava/util/Iterator;
     113: astore        11
     115: aload         11
     117: invokeinterface #230,  1          // InterfaceMethod java/util/Iterator.hasNext:()Z
     122: ifeq          150
     125: aload         11
     127: invokeinterface #234,  1          // InterfaceMethod java/util/Iterator.next:()Ljava/lang/Object;
     132: astore        12
     134: aload         12
     136: astore        13
     138: iconst_0
     139: istore        14
     141: aload_0
     142: aload         13
     144: invokevirtual #243                // Method releaseMessage:(Ljava/lang/Object;)V
     147: goto          115
     150: nop
     151: nop
     152: goto          64
     155: nop
     156: aload_0
     157: getfield      #62                 // Field parentReadLock:Ljava/lang/Object;
     160: astore        4
     162: aload         4
     164: monitorenter
     165: nop
     166: iconst_0
     167: istore        5
     169: aload_0
     170: getfield      #55                 // Field pausedChildren:Ljava/util/Set;
     173: invokeinterface #244,  1          // InterfaceMethod java/util/Set.clear:()V
     178: aload_0
     179: aconst_null
     180: putfield      #248                // Field parentAutoReadBeforePause:Ljava/lang/Boolean;
     183: nop
     184: getstatic     #254                // Field kotlin/Unit.INSTANCE:Lkotlin/Unit;
     187: astore        5
     189: aload         4
     191: monitorexit
     192: goto          203
     195: astore        5
     197: aload         4
     199: monitorexit
     200: aload         5
     202: athrow
     203: aload_0
     204: aload_1
     205: invokespecial #256                // Method io/netty/channel/ChannelInboundHandlerAdapter.channelUnregistered:(Lio/netty/channel/ChannelHandlerContext;)V
     208: return
    Exception table:
       from    to  target type
         165   189   195   any
         195   197   195   any

  public void exceptionCaught(io.netty.channel.ChannelHandlerContext, java.lang.Throwable);
    Code:
       0: aload_1
       1: ldc           #145                // String ctx
       3: invokestatic  #132                // Method kotlin/jvm/internal/Intrinsics.checkNotNullParameter:(Ljava/lang/Object;Ljava/lang/String;)V
       6: aload_2
       7: ldc_w         #274                // String cause
      10: invokestatic  #132                // Method kotlin/jvm/internal/Intrinsics.checkNotNullParameter:(Ljava/lang/Object;Ljava/lang/String;)V
      13: nop
      14: aload_2
      15: ldc_w         #276                // class io/libp2p/core/InternalErrorException
      18: invokestatic  #282                // Method kotlin/jvm/internal/Reflection.getOrCreateKotlinClass:(Ljava/lang/Class;)Lkotlin/reflect/KClass;
      21: invokestatic  #288                // Method io/libp2p/etc/types/OtherExtKt.hasCauseOfType:(Ljava/lang/Throwable;Lkotlin/reflect/KClass;)Z
      24: ifeq          42
      27: invokestatic  #294                // Method io/libp2p/etc/util/netty/mux/AbstractMuxHandlerKt.access$getLog$p:()Lorg/slf4j/Logger;
      30: ldc_w         #296                // String Muxer internal error
      33: aload_2
      34: invokeinterface #302,  3          // InterfaceMethod org/slf4j/Logger.warn:(Ljava/lang/String;Ljava/lang/Throwable;)V
      39: goto          82
      42: aload_2
      43: ldc_w         #304                // class io/libp2p/core/Libp2pException
      46: invokestatic  #282                // Method kotlin/jvm/internal/Reflection.getOrCreateKotlinClass:(Ljava/lang/Class;)Lkotlin/reflect/KClass;
      49: invokestatic  #288                // Method io/libp2p/etc/types/OtherExtKt.hasCauseOfType:(Ljava/lang/Throwable;Lkotlin/reflect/KClass;)Z
      52: ifeq          70
      55: invokestatic  #294                // Method io/libp2p/etc/util/netty/mux/AbstractMuxHandlerKt.access$getLog$p:()Lorg/slf4j/Logger;
      58: ldc_w         #306                // String Muxer exception
      61: aload_2
      62: invokeinterface #309,  3          // InterfaceMethod org/slf4j/Logger.debug:(Ljava/lang/String;Ljava/lang/Throwable;)V
      67: goto          82
      70: invokestatic  #294                // Method io/libp2p/etc/util/netty/mux/AbstractMuxHandlerKt.access$getLog$p:()Lorg/slf4j/Logger;
      73: ldc_w         #311                // String Unexpected exception
      76: aload_2
      77: invokeinterface #302,  3          // InterfaceMethod org/slf4j/Logger.warn:(Ljava/lang/String;Ljava/lang/Throwable;)V
      82: return

  public final io.netty.channel.ChannelHandlerContext getChannelHandlerContext();
    Code:
       0: aload_0
       1: getfield      #94                 // Field ctx:Lio/netty/channel/ChannelHandlerContext;
       4: dup
       5: ifnonnull     20
       8: pop
       9: new           #276                // class io/libp2p/core/InternalErrorException
      12: dup
      13: ldc_w         #315                // String Internal error: handler context should be initialized at this stage
      16: invokespecial #318                // Method io/libp2p/core/InternalErrorException."<init>":(Ljava/lang/String;)V
      19: athrow
      20: areturn

  protected final void childRead(io.libp2p.etc.util.netty.mux.MuxId, TData);
    Code:
       0: aload_1
       1: ldc           #126                // String id
       3: invokestatic  #132                // Method kotlin/jvm/internal/Intrinsics.checkNotNullParameter:(Ljava/lang/Object;Ljava/lang/String;)V
       6: aload_0
       7: getfield      #23                 // Field streamMap:Ljava/util/Map;
      10: aload_1
      11: invokeinterface #325,  2          // InterfaceMethod java/util/Map.get:(Ljava/lang/Object;)Ljava/lang/Object;
      16: checkcast     #327                // class io/libp2p/etc/util/netty/mux/MuxChannel
      19: astore_3
      20: nop
      21: aload_3
      22: ifnonnull     44
      25: aload_0
      26: aload_2
      27: invokevirtual #243                // Method releaseMessage:(Ljava/lang/Object;)V
      30: new           #192                // class io/libp2p/core/ConnectionClosedException
      33: dup
      34: aload_1
      35: invokedynamic #339,  0            // InvokeDynamic #1:makeConcatWithConstants:(Lio/libp2p/etc/util/netty/mux/MuxId;)Ljava/lang/String;
      40: invokespecial #340                // Method io/libp2p/core/ConnectionClosedException."<init>":(Ljava/lang/String;)V
      43: athrow
      44: aload_3
      45: invokevirtual #343                // Method io/libp2p/etc/util/netty/mux/MuxChannel.getRemoteDisconnected:()Z
      48: ifne          88
      51: aload_0
      52: getfield      #52                 // Field pendingInbound:Ljava/util/Map;
      55: aload_1
      56: invokeinterface #325,  2          // InterfaceMethod java/util/Map.get:(Ljava/lang/Object;)Ljava/lang/Object;
      61: checkcast     #236                // class io/libp2p/etc/util/netty/mux/AbstractMuxHandler$PendingInbound
      64: dup
      65: ifnull        83
      68: invokevirtual #346                // Method io/libp2p/etc/util/netty/mux/AbstractMuxHandler$PendingInbound.getRemoteEndPending:()Z
      71: iconst_1
      72: if_icmpne     79
      75: iconst_1
      76: goto          85
      79: iconst_0
      80: goto          85
      83: pop
      84: iconst_0
      85: ifeq          107
      88: aload_0
      89: aload_2
      90: invokevirtual #243                // Method releaseMessage:(Ljava/lang/Object;)V
      93: new           #192                // class io/libp2p/core/ConnectionClosedException
      96: dup
      97: aload_1
      98: invokedynamic #349,  0            // InvokeDynamic #2:makeConcatWithConstants:(Lio/libp2p/etc/util/netty/mux/MuxId;)Ljava/lang/String;
     103: invokespecial #340                // Method io/libp2p/core/ConnectionClosedException."<init>":(Ljava/lang/String;)V
     106: athrow
     107: nop
     108: aload_3
     109: invokevirtual #353                // Method io/libp2p/etc/util/netty/mux/MuxChannel.config:()Lio/netty/channel/ChannelConfig;
     112: invokeinterface #358,  1          // InterfaceMethod io/netty/channel/ChannelConfig.isAutoRead:()Z
     117: istore        4
     119: aload_0
     120: getfield      #52                 // Field pendingInbound:Ljava/util/Map;
     123: aload_1
     124: invokeinterface #325,  2          // InterfaceMethod java/util/Map.get:(Ljava/lang/Object;)Ljava/lang/Object;
     129: checkcast     #236                // class io/libp2p/etc/util/netty/mux/AbstractMuxHandler$PendingInbound
     132: astore        5
     134: iload         4
     136: ifeq          173
     139: aload         5
     141: ifnull        173
     144: aload         5
     146: invokevirtual #361                // Method io/libp2p/etc/util/netty/mux/AbstractMuxHandler$PendingInbound.getDraining:()Z
     149: ifne          173
     152: aload_0
     153: aload_3
     154: invokespecial #365                // Method drainPendingInbound:(Lio/libp2p/etc/util/netty/mux/MuxChannel;)V
     157: aload_3
     158: invokevirtual #366                // Method io/libp2p/etc/util/netty/mux/MuxChannel.isOpen:()Z
     161: ifne          170
     164: aload_0
     165: aload_2
     166: invokevirtual #243                // Method releaseMessage:(Ljava/lang/Object;)V
     169: return
     170: goto          107
     173: iload         4
     175: ifne          183
     178: aload_0
     179: aload_3
     180: invokespecial #369                // Method pauseChild:(Lio/libp2p/etc/util/netty/mux/MuxChannel;)V
     183: iload         4
     185: ifeq          193
     188: aload         5
     190: ifnull        550
     193: aload         5
     195: dup
     196: ifnonnull     232
     199: pop
     200: new           #236                // class io/libp2p/etc/util/netty/mux/AbstractMuxHandler$PendingInbound
     203: dup
     204: invokespecial #370                // Method io/libp2p/etc/util/netty/mux/AbstractMuxHandler$PendingInbound."<init>":()V
     207: astore        7
     209: aload         7
     211: astore        8
     213: iconst_0
     214: istore        9
     216: aload_0
     217: getfield      #52                 // Field pendingInbound:Ljava/util/Map;
     220: aload_1
     221: aload         8
     223: invokeinterface #374,  3          // InterfaceMethod java/util/Map.put:(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
     228: pop
     229: nop
     230: aload         7
     232: astore        6
     234: aload_0
     235: aload_2
     236: invokevirtual #376                // Method pendingChildReadSize:(Ljava/lang/Object;)I
     239: istore        7
     241: aload_0
     242: invokevirtual #378                // Method getMaxPendingChildReadFrames:()Ljava/lang/Integer;
     245: astore        8
     247: aload         6
     249: invokevirtual #240                // Method io/libp2p/etc/util/netty/mux/AbstractMuxHandler$PendingInbound.getMessages:()Ljava/util/ArrayDeque;
     252: invokevirtual #383                // Method java/util/ArrayDeque.peekLast:()Ljava/lang/Object;
     255: astore        9
     257: aload         8
     259: ifnull        282
     262: aload         6
     264: invokevirtual #240                // Method io/libp2p/etc/util/netty/mux/AbstractMuxHandler$PendingInbound.getMessages:()Ljava/util/ArrayDeque;
     267: invokevirtual #386                // Method java/util/ArrayDeque.size:()I
     270: aload         8
     272: invokevirtual #389                // Method java/lang/Integer.intValue:()I
     275: if_icmplt     282
     278: iconst_1
     279: goto          283
     282: iconst_0
     283: istore        10
     285: aload         6
     287: invokevirtual #392                // Method io/libp2p/etc/util/netty/mux/AbstractMuxHandler$PendingInbound.getBytes:()J
     290: iload         7
     292: i2l
     293: ladd
     294: aload_0
     295: invokevirtual #394                // Method getMaxPendingChildReadBytes:()J
     298: lcmp
     299: ifgt          306
     302: iconst_1
     303: goto          307
     306: iconst_0
     307: istore        11
     309: iload         11
     311: ifeq          338
     314: iload         10
     316: ifeq          338
     319: aload         9
     321: ifnull        338
     324: aload_0
     325: aload         9
     327: aload_2
     328: invokevirtual #396                // Method coalescePendingChildRead:(Ljava/lang/Object;Ljava/lang/Object;)Z
     331: ifeq          338
     334: iconst_1
     335: goto          339
     338: iconst_0
     339: istore        12
     341: iload         11
     343: ifeq          356
     346: iload         10
     348: ifeq          481
     351: iload         12
     353: ifne          481
     356: aload_0
     357: aload_2
     358: invokevirtual #243                // Method releaseMessage:(Ljava/lang/Object;)V
     361: new           #192                // class io/libp2p/core/ConnectionClosedException
     364: dup
     365: aload_1
     366: aload         6
     368: invokevirtual #240                // Method io/libp2p/etc/util/netty/mux/AbstractMuxHandler$PendingInbound.getMessages:()Ljava/util/ArrayDeque;
     371: invokevirtual #386                // Method java/util/ArrayDeque.size:()I
     374: iconst_1
     375: iadd
     376: aload         6
     378: invokevirtual #392                // Method io/libp2p/etc/util/netty/mux/AbstractMuxHandler$PendingInbound.getBytes:()J
     381: iload         7
     383: i2l
     384: ladd
     385: aload         8
     387: aload_0
     388: invokevirtual #394                // Method getMaxPendingChildReadBytes:()J
     391: invokedynamic #401,  0            // InvokeDynamic #3:makeConcatWithConstants:(Lio/libp2p/etc/util/netty/mux/MuxId;IJLjava/lang/Integer;J)Ljava/lang/String;
     396: invokespecial #340                // Method io/libp2p/core/ConnectionClosedException."<init>":(Ljava/lang/String;)V
     399: astore        13
     401: invokestatic  #294                // Method io/libp2p/etc/util/netty/mux/AbstractMuxHandlerKt.access$getLog$p:()Lorg/slf4j/Logger;
     404: aload         13
     406: invokevirtual #405                // Method io/libp2p/core/ConnectionClosedException.getMessage:()Ljava/lang/String;
     409: invokeinterface #407,  2          // InterfaceMethod org/slf4j/Logger.warn:(Ljava/lang/String;)V
     414: aload_0
     415: getfield      #52                 // Field pendingInbound:Ljava/util/Map;
     418: aload_1
     419: invokeinterface #410,  2          // InterfaceMethod java/util/Map.remove:(Ljava/lang/Object;)Ljava/lang/Object;
     424: pop
     425: aload         6
     427: invokevirtual #240                // Method io/libp2p/etc/util/netty/mux/AbstractMuxHandler$PendingInbound.getMessages:()Ljava/util/ArrayDeque;
     430: checkcast     #412                // class java/util/Collection
     433: invokeinterface #415,  1          // InterfaceMethod java/util/Collection.isEmpty:()Z
     438: ifne          445
     441: iconst_1
     442: goto          446
     445: iconst_0
     446: ifeq          464
     449: aload_0
     450: aload         6
     452: invokevirtual #240                // Method io/libp2p/etc/util/netty/mux/AbstractMuxHandler$PendingInbound.getMessages:()Ljava/util/ArrayDeque;
     455: invokevirtual #418                // Method java/util/ArrayDeque.removeFirst:()Ljava/lang/Object;
     458: invokevirtual #243                // Method releaseMessage:(Ljava/lang/Object;)V
     461: goto          425
     464: aload         6
     466: lconst_0
     467: invokevirtual #422                // Method io/libp2p/etc/util/netty/mux/AbstractMuxHandler$PendingInbound.setBytes:(J)V
     470: aload_0
     471: aload_3
     472: aload         13
     474: checkcast     #195                // class java/lang/Throwable
     477: invokevirtual #426                // Method onChildReadOverflow:(Lio/libp2p/etc/util/netty/mux/MuxChannel;Ljava/lang/Throwable;)V
     480: return
     481: iload         12
     483: ifne          516
     486: aload         9
     488: ifnull        497
     491: aload_0
     492: aload         9
     494: invokevirtual #428                // Method sealPendingChildRead:(Ljava/lang/Object;)V
     497: aload         6
     499: invokevirtual #240                // Method io/libp2p/etc/util/netty/mux/AbstractMuxHandler$PendingInbound.getMessages:()Ljava/util/ArrayDeque;
     502: aload_0
     503: aload_2
     504: aload         6
     506: invokevirtual #392                // Method io/libp2p/etc/util/netty/mux/AbstractMuxHandler$PendingInbound.getBytes:()J
     509: invokevirtual #430                // Method preparePendingChildRead:(Ljava/lang/Object;J)Ljava/lang/Object;
     512: invokevirtual #433                // Method java/util/ArrayDeque.add:(Ljava/lang/Object;)Z
     515: pop
     516: aload         6
     518: aload         6
     520: invokevirtual #392                // Method io/libp2p/etc/util/netty/mux/AbstractMuxHandler$PendingInbound.getBytes:()J
     523: iload         7
     525: i2l
     526: ladd
     527: invokevirtual #422                // Method io/libp2p/etc/util/netty/mux/AbstractMuxHandler$PendingInbound.setBytes:(J)V
     530: aload_3
     531: invokevirtual #353                // Method io/libp2p/etc/util/netty/mux/MuxChannel.config:()Lio/netty/channel/ChannelConfig;
     534: invokeinterface #358,  1          // InterfaceMethod io/netty/channel/ChannelConfig.isAutoRead:()Z
     539: ifeq          557
     542: aload_0
     543: aload_3
     544: invokespecial #365                // Method drainPendingInbound:(Lio/libp2p/etc/util/netty/mux/MuxChannel;)V
     547: goto          557
     550: aload_0
     551: aload_3
     552: aload_2
     553: iconst_0
     554: invokespecial #437                // Method deliverToChild:(Lio/libp2p/etc/util/netty/mux/MuxChannel;Ljava/lang/Object;Z)V
     557: return

  protected void onChildReadOverflow(io.libp2p.etc.util.netty.mux.MuxChannel<TData>, java.lang.Throwable);
    Code:
       0: aload_1
       1: ldc_w         #453                // String child
       4: invokestatic  #132                // Method kotlin/jvm/internal/Intrinsics.checkNotNullParameter:(Ljava/lang/Object;Ljava/lang/String;)V
       7: aload_2
       8: ldc_w         #274                // String cause
      11: invokestatic  #132                // Method kotlin/jvm/internal/Intrinsics.checkNotNullParameter:(Ljava/lang/Object;Ljava/lang/String;)V
      14: aload_1
      15: invokevirtual #456                // Method io/libp2p/etc/util/netty/mux/MuxChannel.close:()Lio/netty/channel/ChannelFuture;
      18: pop
      19: return

  public final void childAutoReadChanged$libp2p(io.libp2p.etc.util.netty.mux.MuxChannel<TData>);
    Code:
       0: aload_1
       1: ldc_w         #453                // String child
       4: invokestatic  #132                // Method kotlin/jvm/internal/Intrinsics.checkNotNullParameter:(Ljava/lang/Object;Ljava/lang/String;)V
       7: aload_0
       8: invokevirtual #460                // Method getChannelHandlerContext:()Lio/netty/channel/ChannelHandlerContext;
      11: astore_2
      12: aload_1
      13: aload_0
      14: invokedynamic #470,  0            // InvokeDynamic #4:run:(Lio/libp2p/etc/util/netty/mux/MuxChannel;Lio/libp2p/etc/util/netty/mux/AbstractMuxHandler;)Ljava/lang/Runnable;
      19: astore_3
      20: aload_2
      21: invokeinterface #474,  1          // InterfaceMethod io/netty/channel/ChannelHandlerContext.executor:()Lio/netty/util/concurrent/EventExecutor;
      26: invokeinterface #479,  1          // InterfaceMethod io/netty/util/concurrent/EventExecutor.inEventLoop:()Z
      31: ifeq          43
      34: aload_3
      35: invokeinterface #483,  1          // InterfaceMethod java/lang/Runnable.run:()V
      40: goto          55
      43: aload_2
      44: invokeinterface #474,  1          // InterfaceMethod io/netty/channel/ChannelHandlerContext.executor:()Lio/netty/util/concurrent/EventExecutor;
      49: aload_3
      50: invokeinterface #487,  2          // InterfaceMethod io/netty/util/concurrent/EventExecutor.execute:(Ljava/lang/Runnable;)V
      55: return

  private final void pauseChild(io.libp2p.etc.util.netty.mux.MuxChannel<TData>);
    Code:
       0: aload_0
       1: invokevirtual #492                // Method getPauseParentReadsForPausedChild:()Z
       4: ifne          8
       7: return
       8: aload_0
       9: getfield      #62                 // Field parentReadLock:Ljava/lang/Object;
      12: astore_2
      13: aload_2
      14: monitorenter
      15: nop
      16: iconst_0
      17: istore_3
      18: aload_1
      19: invokevirtual #366                // Method io/libp2p/etc/util/netty/mux/MuxChannel.isOpen:()Z
      22: ifeq          53
      25: aload_1
      26: invokevirtual #353                // Method io/libp2p/etc/util/netty/mux/MuxChannel.config:()Lio/netty/channel/ChannelConfig;
      29: invokeinterface #358,  1          // InterfaceMethod io/netty/channel/ChannelConfig.isAutoRead:()Z
      34: ifne          53
      37: aload_0
      38: getfield      #55                 // Field pausedChildren:Ljava/util/Set;
      41: aload_1
      42: invokevirtual #496                // Method io/libp2p/etc/util/netty/mux/MuxChannel.getId:()Lio/libp2p/etc/util/netty/mux/MuxId;
      45: invokeinterface #497,  2          // InterfaceMethod java/util/Set.add:(Ljava/lang/Object;)Z
      50: ifne          56
      53: aload_2
      54: monitorexit
      55: return
      56: aload_0
      57: invokevirtual #460                // Method getChannelHandlerContext:()Lio/netty/channel/ChannelHandlerContext;
      60: invokeinterface #153,  1          // InterfaceMethod io/netty/channel/ChannelHandlerContext.channel:()Lio/netty/channel/Channel;
      65: astore        4
      67: aload_0
      68: getfield      #248                // Field parentAutoReadBeforePause:Ljava/lang/Boolean;
      71: ifnonnull     93
      74: aload_0
      75: aload         4
      77: invokeinterface #498,  1          // InterfaceMethod io/netty/channel/Channel.config:()Lio/netty/channel/ChannelConfig;
      82: invokeinterface #358,  1          // InterfaceMethod io/netty/channel/ChannelConfig.isAutoRead:()Z
      87: invokestatic  #503                // Method java/lang/Boolean.valueOf:(Z)Ljava/lang/Boolean;
      90: putfield      #248                // Field parentAutoReadBeforePause:Ljava/lang/Boolean;
      93: aload         4
      95: invokeinterface #498,  1          // InterfaceMethod io/netty/channel/Channel.config:()Lio/netty/channel/ChannelConfig;
     100: iconst_0
     101: invokeinterface #507,  2          // InterfaceMethod io/netty/channel/ChannelConfig.setAutoRead:(Z)Lio/netty/channel/ChannelConfig;
     106: pop
     107: nop
     108: getstatic     #254                // Field kotlin/Unit.INSTANCE:Lkotlin/Unit;
     111: astore_3
     112: aload_2
     113: monitorexit
     114: goto          122
     117: astore_3
     118: aload_2
     119: monitorexit
     120: aload_3
     121: athrow
     122: return
    Exception table:
       from    to  target type
          15    53   117   any
          56   112   117   any
         117   118   117   any

  private final void restoreParentReadsIfPossible();
    Code:
       0: aload_0
       1: getfield      #55                 // Field pausedChildren:Ljava/util/Set;
       4: checkcast     #412                // class java/util/Collection
       7: invokeinterface #415,  1          // InterfaceMethod java/util/Collection.isEmpty:()Z
      12: ifne          19
      15: iconst_1
      16: goto          20
      19: iconst_0
      20: ifeq          24
      23: return
      24: aload_0
      25: getfield      #248                // Field parentAutoReadBeforePause:Ljava/lang/Boolean;
      28: dup
      29: ifnull        38
      32: invokevirtual #514                // Method java/lang/Boolean.booleanValue:()Z
      35: goto          40
      38: pop
      39: return
      40: istore_1
      41: aload_0
      42: aconst_null
      43: putfield      #248                // Field parentAutoReadBeforePause:Ljava/lang/Boolean;
      46: aload_0
      47: invokevirtual #460                // Method getChannelHandlerContext:()Lio/netty/channel/ChannelHandlerContext;
      50: invokeinterface #153,  1          // InterfaceMethod io/netty/channel/ChannelHandlerContext.channel:()Lio/netty/channel/Channel;
      55: astore_2
      56: iload_1
      57: ifeq          82
      60: aload_2
      61: invokeinterface #190,  1          // InterfaceMethod io/netty/channel/Channel.isOpen:()Z
      66: ifeq          82
      69: aload_2
      70: invokeinterface #498,  1          // InterfaceMethod io/netty/channel/Channel.config:()Lio/netty/channel/ChannelConfig;
      75: iconst_1
      76: invokeinterface #507,  2          // InterfaceMethod io/netty/channel/ChannelConfig.setAutoRead:(Z)Lio/netty/channel/ChannelConfig;
      81: pop
      82: return

  private final void drainPendingInbound(io.libp2p.etc.util.netty.mux.MuxChannel<TData>);
    Code:
       0: aload_0
       1: getfield      #52                 // Field pendingInbound:Ljava/util/Map;
       4: aload_1
       5: invokevirtual #496                // Method io/libp2p/etc/util/netty/mux/MuxChannel.getId:()Lio/libp2p/etc/util/netty/mux/MuxId;
       8: invokeinterface #325,  2          // InterfaceMethod java/util/Map.get:(Ljava/lang/Object;)Ljava/lang/Object;
      13: checkcast     #236                // class io/libp2p/etc/util/netty/mux/AbstractMuxHandler$PendingInbound
      16: dup
      17: ifnonnull     22
      20: pop
      21: return
      22: astore_2
      23: aload_2
      24: invokevirtual #361                // Method io/libp2p/etc/util/netty/mux/AbstractMuxHandler$PendingInbound.getDraining:()Z
      27: ifeq          31
      30: return
      31: iconst_0
      32: istore_3
      33: aload_2
      34: iconst_1
      35: invokevirtual #519                // Method io/libp2p/etc/util/netty/mux/AbstractMuxHandler$PendingInbound.setDraining:(Z)V
      38: nop
      39: aload_1
      40: invokevirtual #366                // Method io/libp2p/etc/util/netty/mux/MuxChannel.isOpen:()Z
      43: ifeq          125
      46: aload_1
      47: invokevirtual #353                // Method io/libp2p/etc/util/netty/mux/MuxChannel.config:()Lio/netty/channel/ChannelConfig;
      50: invokeinterface #358,  1          // InterfaceMethod io/netty/channel/ChannelConfig.isAutoRead:()Z
      55: ifeq          125
      58: aload_2
      59: invokevirtual #240                // Method io/libp2p/etc/util/netty/mux/AbstractMuxHandler$PendingInbound.getMessages:()Ljava/util/ArrayDeque;
      62: checkcast     #412                // class java/util/Collection
      65: invokeinterface #415,  1          // InterfaceMethod java/util/Collection.isEmpty:()Z
      70: ifne          77
      73: iconst_1
      74: goto          78
      77: iconst_0
      78: ifeq          125
      81: aload_2
      82: invokevirtual #240                // Method io/libp2p/etc/util/netty/mux/AbstractMuxHandler$PendingInbound.getMessages:()Ljava/util/ArrayDeque;
      85: invokevirtual #418                // Method java/util/ArrayDeque.removeFirst:()Ljava/lang/Object;
      88: astore        4
      90: aload_2
      91: aload_2
      92: invokevirtual #392                // Method io/libp2p/etc/util/netty/mux/AbstractMuxHandler$PendingInbound.getBytes:()J
      95: aload_0
      96: aload         4
      98: invokevirtual #376                // Method pendingChildReadSize:(Ljava/lang/Object;)I
     101: i2l
     102: lsub
     103: invokevirtual #422                // Method io/libp2p/etc/util/netty/mux/AbstractMuxHandler$PendingInbound.setBytes:(J)V
     106: aload_0
     107: aload         4
     109: invokevirtual #428                // Method sealPendingChildRead:(Ljava/lang/Object;)V
     112: iconst_1
     113: istore_3
     114: aload_0
     115: aload_1
     116: aload         4
     118: iconst_1
     119: invokespecial #437                // Method deliverToChild:(Lio/libp2p/etc/util/netty/mux/MuxChannel;Ljava/lang/Object;Z)V
     122: goto          39
     125: aload_2
     126: iconst_0
     127: invokevirtual #519                // Method io/libp2p/etc/util/netty/mux/AbstractMuxHandler$PendingInbound.setDraining:(Z)V
     130: aload_2
     131: invokevirtual #240                // Method io/libp2p/etc/util/netty/mux/AbstractMuxHandler$PendingInbound.getMessages:()Ljava/util/ArrayDeque;
     134: invokevirtual #520                // Method java/util/ArrayDeque.isEmpty:()Z
     137: ifeq          151
     140: aload_2
     141: invokevirtual #346                // Method io/libp2p/etc/util/netty/mux/AbstractMuxHandler$PendingInbound.getRemoteEndPending:()Z
     144: ifeq          151
     147: iconst_1
     148: goto          152
     151: iconst_0
     152: istore        4
     154: aload_2
     155: invokevirtual #240                // Method io/libp2p/etc/util/netty/mux/AbstractMuxHandler$PendingInbound.getMessages:()Ljava/util/ArrayDeque;
     158: invokevirtual #520                // Method java/util/ArrayDeque.isEmpty:()Z
     161: ifeq          200
     164: iload         4
     166: ifne          200
     169: aload_0
     170: getfield      #52                 // Field pendingInbound:Ljava/util/Map;
     173: aload_1
     174: invokevirtual #496                // Method io/libp2p/etc/util/netty/mux/MuxChannel.getId:()Lio/libp2p/etc/util/netty/mux/MuxId;
     177: invokeinterface #325,  2          // InterfaceMethod java/util/Map.get:(Ljava/lang/Object;)Ljava/lang/Object;
     182: aload_2
     183: if_acmpne     200
     186: aload_0
     187: getfield      #52                 // Field pendingInbound:Ljava/util/Map;
     190: aload_1
     191: invokevirtual #496                // Method io/libp2p/etc/util/netty/mux/MuxChannel.getId:()Lio/libp2p/etc/util/netty/mux/MuxId;
     194: invokeinterface #410,  2          // InterfaceMethod java/util/Map.remove:(Ljava/lang/Object;)Ljava/lang/Object;
     199: pop
     200: nop
     201: iload_3
     202: ifeq          222
     205: aload_1
     206: invokevirtual #366                // Method io/libp2p/etc/util/netty/mux/MuxChannel.isOpen:()Z
     209: ifeq          222
     212: aload_1
     213: invokevirtual #524                // Method io/libp2p/etc/util/netty/mux/MuxChannel.pipeline:()Lio/netty/channel/ChannelPipeline;
     216: invokeinterface #529,  1          // InterfaceMethod io/netty/channel/ChannelPipeline.fireChannelReadComplete:()Lio/netty/channel/ChannelPipeline;
     221: pop
     222: iload         4
     224: ifeq          309
     227: nop
     228: aload_1
     229: invokevirtual #366                // Method io/libp2p/etc/util/netty/mux/MuxChannel.isOpen:()Z
     232: ifeq          239
     235: aload_1
     236: invokevirtual #532                // Method io/libp2p/etc/util/netty/mux/MuxChannel.onRemoteDisconnected:()V
     239: aload_0
     240: getfield      #52                 // Field pendingInbound:Ljava/util/Map;
     243: aload_1
     244: invokevirtual #496                // Method io/libp2p/etc/util/netty/mux/MuxChannel.getId:()Lio/libp2p/etc/util/netty/mux/MuxId;
     247: invokeinterface #325,  2          // InterfaceMethod java/util/Map.get:(Ljava/lang/Object;)Ljava/lang/Object;
     252: aload_2
     253: if_acmpne     270
     256: aload_0
     257: getfield      #52                 // Field pendingInbound:Ljava/util/Map;
     260: aload_1
     261: invokevirtual #496                // Method io/libp2p/etc/util/netty/mux/MuxChannel.getId:()Lio/libp2p/etc/util/netty/mux/MuxId;
     264: invokeinterface #410,  2          // InterfaceMethod java/util/Map.remove:(Ljava/lang/Object;)Ljava/lang/Object;
     269: pop
     270: goto          309
     273: astore        5
     275: aload_0
     276: getfield      #52                 // Field pendingInbound:Ljava/util/Map;
     279: aload_1
     280: invokevirtual #496                // Method io/libp2p/etc/util/netty/mux/MuxChannel.getId:()Lio/libp2p/etc/util/netty/mux/MuxId;
     283: invokeinterface #325,  2          // InterfaceMethod java/util/Map.get:(Ljava/lang/Object;)Ljava/lang/Object;
     288: aload_2
     289: if_acmpne     306
     292: aload_0
     293: getfield      #52                 // Field pendingInbound:Ljava/util/Map;
     296: aload_1
     297: invokevirtual #496                // Method io/libp2p/etc/util/netty/mux/MuxChannel.getId:()Lio/libp2p/etc/util/netty/mux/MuxId;
     300: invokeinterface #410,  2          // InterfaceMethod java/util/Map.remove:(Ljava/lang/Object;)Ljava/lang/Object;
     305: pop
     306: aload         5
     308: athrow
     309: goto          404
     312: astore        5
     314: iload         4
     316: ifeq          401
     319: nop
     320: aload_1
     321: invokevirtual #366                // Method io/libp2p/etc/util/netty/mux/MuxChannel.isOpen:()Z
     324: ifeq          331
     327: aload_1
     328: invokevirtual #532                // Method io/libp2p/etc/util/netty/mux/MuxChannel.onRemoteDisconnected:()V
     331: aload_0
     332: getfield      #52                 // Field pendingInbound:Ljava/util/Map;
     335: aload_1
     336: invokevirtual #496                // Method io/libp2p/etc/util/netty/mux/MuxChannel.getId:()Lio/libp2p/etc/util/netty/mux/MuxId;
     339: invokeinterface #325,  2          // InterfaceMethod java/util/Map.get:(Ljava/lang/Object;)Ljava/lang/Object;
     344: aload_2
     345: if_acmpne     362
     348: aload_0
     349: getfield      #52                 // Field pendingInbound:Ljava/util/Map;
     352: aload_1
     353: invokevirtual #496                // Method io/libp2p/etc/util/netty/mux/MuxChannel.getId:()Lio/libp2p/etc/util/netty/mux/MuxId;
     356: invokeinterface #410,  2          // InterfaceMethod java/util/Map.remove:(Ljava/lang/Object;)Ljava/lang/Object;
     361: pop
     362: goto          401
     365: astore        6
     367: aload_0
     368: getfield      #52                 // Field pendingInbound:Ljava/util/Map;
     371: aload_1
     372: invokevirtual #496                // Method io/libp2p/etc/util/netty/mux/MuxChannel.getId:()Lio/libp2p/etc/util/netty/mux/MuxId;
     375: invokeinterface #325,  2          // InterfaceMethod java/util/Map.get:(Ljava/lang/Object;)Ljava/lang/Object;
     380: aload_2
     381: if_acmpne     398
     384: aload_0
     385: getfield      #52                 // Field pendingInbound:Ljava/util/Map;
     388: aload_1
     389: invokevirtual #496                // Method io/libp2p/etc/util/netty/mux/MuxChannel.getId:()Lio/libp2p/etc/util/netty/mux/MuxId;
     392: invokeinterface #410,  2          // InterfaceMethod java/util/Map.remove:(Ljava/lang/Object;)Ljava/lang/Object;
     397: pop
     398: aload         6
     400: athrow
     401: aload         5
     403: athrow
     404: goto          691
     407: astore        4
     409: aload_2
     410: iconst_0
     411: invokevirtual #519                // Method io/libp2p/etc/util/netty/mux/AbstractMuxHandler$PendingInbound.setDraining:(Z)V
     414: aload_2
     415: invokevirtual #240                // Method io/libp2p/etc/util/netty/mux/AbstractMuxHandler$PendingInbound.getMessages:()Ljava/util/ArrayDeque;
     418: invokevirtual #520                // Method java/util/ArrayDeque.isEmpty:()Z
     421: ifeq          435
     424: aload_2
     425: invokevirtual #346                // Method io/libp2p/etc/util/netty/mux/AbstractMuxHandler$PendingInbound.getRemoteEndPending:()Z
     428: ifeq          435
     431: iconst_1
     432: goto          436
     435: iconst_0
     436: istore        5
     438: aload_2
     439: invokevirtual #240                // Method io/libp2p/etc/util/netty/mux/AbstractMuxHandler$PendingInbound.getMessages:()Ljava/util/ArrayDeque;
     442: invokevirtual #520                // Method java/util/ArrayDeque.isEmpty:()Z
     445: ifeq          484
     448: iload         5
     450: ifne          484
     453: aload_0
     454: getfield      #52                 // Field pendingInbound:Ljava/util/Map;
     457: aload_1
     458: invokevirtual #496                // Method io/libp2p/etc/util/netty/mux/MuxChannel.getId:()Lio/libp2p/etc/util/netty/mux/MuxId;
     461: invokeinterface #325,  2          // InterfaceMethod java/util/Map.get:(Ljava/lang/Object;)Ljava/lang/Object;
     466: aload_2
     467: if_acmpne     484
     470: aload_0
     471: getfield      #52                 // Field pendingInbound:Ljava/util/Map;
     474: aload_1
     475: invokevirtual #496                // Method io/libp2p/etc/util/netty/mux/MuxChannel.getId:()Lio/libp2p/etc/util/netty/mux/MuxId;
     478: invokeinterface #410,  2          // InterfaceMethod java/util/Map.remove:(Ljava/lang/Object;)Ljava/lang/Object;
     483: pop
     484: nop
     485: iload_3
     486: ifeq          506
     489: aload_1
     490: invokevirtual #366                // Method io/libp2p/etc/util/netty/mux/MuxChannel.isOpen:()Z
     493: ifeq          506
     496: aload_1
     497: invokevirtual #524                // Method io/libp2p/etc/util/netty/mux/MuxChannel.pipeline:()Lio/netty/channel/ChannelPipeline;
     500: invokeinterface #529,  1          // InterfaceMethod io/netty/channel/ChannelPipeline.fireChannelReadComplete:()Lio/netty/channel/ChannelPipeline;
     505: pop
     506: iload         5
     508: ifeq          593
     511: nop
     512: aload_1
     513: invokevirtual #366                // Method io/libp2p/etc/util/netty/mux/MuxChannel.isOpen:()Z
     516: ifeq          523
     519: aload_1
     520: invokevirtual #532                // Method io/libp2p/etc/util/netty/mux/MuxChannel.onRemoteDisconnected:()V
     523: aload_0
     524: getfield      #52                 // Field pendingInbound:Ljava/util/Map;
     527: aload_1
     528: invokevirtual #496                // Method io/libp2p/etc/util/netty/mux/MuxChannel.getId:()Lio/libp2p/etc/util/netty/mux/MuxId;
     531: invokeinterface #325,  2          // InterfaceMethod java/util/Map.get:(Ljava/lang/Object;)Ljava/lang/Object;
     536: aload_2
     537: if_acmpne     554
     540: aload_0
     541: getfield      #52                 // Field pendingInbound:Ljava/util/Map;
     544: aload_1
     545: invokevirtual #496                // Method io/libp2p/etc/util/netty/mux/MuxChannel.getId:()Lio/libp2p/etc/util/netty/mux/MuxId;
     548: invokeinterface #410,  2          // InterfaceMethod java/util/Map.remove:(Ljava/lang/Object;)Ljava/lang/Object;
     553: pop
     554: goto          593
     557: astore        6
     559: aload_0
     560: getfield      #52                 // Field pendingInbound:Ljava/util/Map;
     563: aload_1
     564: invokevirtual #496                // Method io/libp2p/etc/util/netty/mux/MuxChannel.getId:()Lio/libp2p/etc/util/netty/mux/MuxId;
     567: invokeinterface #325,  2          // InterfaceMethod java/util/Map.get:(Ljava/lang/Object;)Ljava/lang/Object;
     572: aload_2
     573: if_acmpne     590
     576: aload_0
     577: getfield      #52                 // Field pendingInbound:Ljava/util/Map;
     580: aload_1
     581: invokevirtual #496                // Method io/libp2p/etc/util/netty/mux/MuxChannel.getId:()Lio/libp2p/etc/util/netty/mux/MuxId;
     584: invokeinterface #410,  2          // InterfaceMethod java/util/Map.remove:(Ljava/lang/Object;)Ljava/lang/Object;
     589: pop
     590: aload         6
     592: athrow
     593: goto          688
     596: astore        6
     598: iload         5
     600: ifeq          685
     603: nop
     604: aload_1
     605: invokevirtual #366                // Method io/libp2p/etc/util/netty/mux/MuxChannel.isOpen:()Z
     608: ifeq          615
     611: aload_1
     612: invokevirtual #532                // Method io/libp2p/etc/util/netty/mux/MuxChannel.onRemoteDisconnected:()V
     615: aload_0
     616: getfield      #52                 // Field pendingInbound:Ljava/util/Map;
     619: aload_1
     620: invokevirtual #496                // Method io/libp2p/etc/util/netty/mux/MuxChannel.getId:()Lio/libp2p/etc/util/netty/mux/MuxId;
     623: invokeinterface #325,  2          // InterfaceMethod java/util/Map.get:(Ljava/lang/Object;)Ljava/lang/Object;
     628: aload_2
     629: if_acmpne     646
     632: aload_0
     633: getfield      #52                 // Field pendingInbound:Ljava/util/Map;
     636: aload_1
     637: invokevirtual #496                // Method io/libp2p/etc/util/netty/mux/MuxChannel.getId:()Lio/libp2p/etc/util/netty/mux/MuxId;
     640: invokeinterface #410,  2          // InterfaceMethod java/util/Map.remove:(Ljava/lang/Object;)Ljava/lang/Object;
     645: pop
     646: goto          685
     649: astore        7
     651: aload_0
     652: getfield      #52                 // Field pendingInbound:Ljava/util/Map;
     655: aload_1
     656: invokevirtual #496                // Method io/libp2p/etc/util/netty/mux/MuxChannel.getId:()Lio/libp2p/etc/util/netty/mux/MuxId;
     659: invokeinterface #325,  2          // InterfaceMethod java/util/Map.get:(Ljava/lang/Object;)Ljava/lang/Object;
     664: aload_2
     665: if_acmpne     682
     668: aload_0
     669: getfield      #52                 // Field pendingInbound:Ljava/util/Map;
     672: aload_1
     673: invokevirtual #496                // Method io/libp2p/etc/util/netty/mux/MuxChannel.getId:()Lio/libp2p/etc/util/netty/mux/MuxId;
     676: invokeinterface #410,  2          // InterfaceMethod java/util/Map.remove:(Ljava/lang/Object;)Ljava/lang/Object;
     681: pop
     682: aload         7
     684: athrow
     685: aload         6
     687: athrow
     688: aload         4
     690: athrow
     691: return
    Exception table:
       from    to  target type
         227   239   273   any
         273   275   273   any
         319   331   365   any
         365   367   365   any
         200   222   312   any
         312   314   312   any
         511   523   557   any
         557   559   557   any
         603   615   649   any
         649   651   649   any
         484   506   596   any
         596   598   596   any
          38   125   407   any
         407   409   407   any

  private final void deliverToChild(io.libp2p.etc.util.netty.mux.MuxChannel<TData>, TData, boolean);
    Code:
       0: aload_0
       1: aload_2
       2: invokevirtual #376                // Method pendingChildReadSize:(Ljava/lang/Object;)I
       5: istore        4
       7: iload_3
       8: ifne          28
      11: aload_0
      12: getfield      #49                 // Field pendingReadComplete:Ljava/util/Set;
      15: checkcast     #412                // class java/util/Collection
      18: aload_1
      19: invokevirtual #496                // Method io/libp2p/etc/util/netty/mux/MuxChannel.getId:()Lio/libp2p/etc/util/netty/mux/MuxId;
      22: invokeinterface #536,  2          // InterfaceMethod java/util/Collection.add:(Ljava/lang/Object;)Z
      27: pop
      28: aload_1
      29: invokevirtual #524                // Method io/libp2p/etc/util/netty/mux/MuxChannel.pipeline:()Lio/netty/channel/ChannelPipeline;
      32: aload_2
      33: invokeinterface #540,  2          // InterfaceMethod io/netty/channel/ChannelPipeline.fireChannelRead:(Ljava/lang/Object;)Lio/netty/channel/ChannelPipeline;
      38: pop
      39: aload_1
      40: invokevirtual #366                // Method io/libp2p/etc/util/netty/mux/MuxChannel.isOpen:()Z
      43: ifeq          56
      46: aload_0
      47: aload_1
      48: invokevirtual #496                // Method io/libp2p/etc/util/netty/mux/MuxChannel.getId:()Lio/libp2p/etc/util/netty/mux/MuxId;
      51: iload         4
      53: invokevirtual #542                // Method onChildReadDelivered:(Lio/libp2p/etc/util/netty/mux/MuxId;I)V
      56: return

  public void channelReadComplete(io.netty.channel.ChannelHandlerContext);
    Code:
       0: aload_1
       1: ldc           #145                // String ctx
       3: invokestatic  #132                // Method kotlin/jvm/internal/Intrinsics.checkNotNullParameter:(Ljava/lang/Object;Ljava/lang/String;)V
       6: aload_0
       7: getfield      #49                 // Field pendingReadComplete:Ljava/util/Set;
      10: checkcast     #212                // class java/lang/Iterable
      13: astore_2
      14: iconst_0
      15: istore_3
      16: aload_2
      17: invokeinterface #225,  1          // InterfaceMethod java/lang/Iterable.iterator:()Ljava/util/Iterator;
      22: astore        4
      24: aload         4
      26: invokeinterface #230,  1          // InterfaceMethod java/util/Iterator.hasNext:()Z
      31: ifeq          91
      34: aload         4
      36: invokeinterface #234,  1          // InterfaceMethod java/util/Iterator.next:()Ljava/lang/Object;
      41: astore        5
      43: aload         5
      45: checkcast     #451                // class io/libp2p/etc/util/netty/mux/MuxId
      48: astore        6
      50: iconst_0
      51: istore        7
      53: aload_0
      54: getfield      #23                 // Field streamMap:Ljava/util/Map;
      57: aload         6
      59: invokeinterface #325,  2          // InterfaceMethod java/util/Map.get:(Ljava/lang/Object;)Ljava/lang/Object;
      64: checkcast     #327                // class io/libp2p/etc/util/netty/mux/MuxChannel
      67: dup
      68: ifnull        87
      71: invokevirtual #524                // Method io/libp2p/etc/util/netty/mux/MuxChannel.pipeline:()Lio/netty/channel/ChannelPipeline;
      74: dup
      75: ifnull        87
      78: invokeinterface #529,  1          // InterfaceMethod io/netty/channel/ChannelPipeline.fireChannelReadComplete:()Lio/netty/channel/ChannelPipeline;
      83: pop
      84: goto          24
      87: pop
      88: goto          24
      91: nop
      92: aload_0
      93: getfield      #49                 // Field pendingReadComplete:Ljava/util/Set;
      96: invokeinterface #244,  1          // InterfaceMethod java/util/Set.clear:()V
     101: return

  public abstract void releaseMessage(TData);

  public java.lang.Integer pendingChildWriteSize$libp2p(TData);
    Code:
       0: aconst_null
       1: areturn

  public java.lang.Throwable onPendingChildWrite$libp2p(io.libp2p.etc.util.netty.mux.MuxChannel<TData>, int);
    Code:
       0: aload_1
       1: ldc_w         #453                // String child
       4: invokestatic  #132                // Method kotlin/jvm/internal/Intrinsics.checkNotNullParameter:(Ljava/lang/Object;Ljava/lang/String;)V
       7: aconst_null
       8: areturn

  public void onPendingChildWriteComplete$libp2p(io.libp2p.etc.util.netty.mux.MuxChannel<TData>, TData, int);
    Code:
       0: aload_1
       1: ldc_w         #453                // String child
       4: invokestatic  #132                // Method kotlin/jvm/internal/Intrinsics.checkNotNullParameter:(Ljava/lang/Object;Ljava/lang/String;)V
       7: return

  public abstract io.netty.channel.ChannelFuture onChildWrite(io.libp2p.etc.util.netty.mux.MuxChannel<TData>, TData);

  protected final void onRemoteOpen(io.libp2p.etc.util.netty.mux.MuxId);
    Code:
       0: aload_1
       1: ldc           #126                // String id
       3: invokestatic  #132                // Method kotlin/jvm/internal/Intrinsics.checkNotNullParameter:(Ljava/lang/Object;Ljava/lang/String;)V
       6: aload_0
       7: invokevirtual #561                // Method getInboundInitializer:()Lkotlin/jvm/functions/Function1;
      10: astore_2
      11: aload_0
      12: getfield      #23                 // Field streamMap:Ljava/util/Map;
      15: aload_1
      16: invokeinterface #564,  2          // InterfaceMethod java/util/Map.containsKey:(Ljava/lang/Object;)Z
      21: ifeq          48
      24: aload_0
      25: invokevirtual #460                // Method getChannelHandlerContext:()Lio/netty/channel/ChannelHandlerContext;
      28: invokeinterface #565,  1          // InterfaceMethod io/netty/channel/ChannelHandlerContext.close:()Lio/netty/channel/ChannelFuture;
      33: pop
      34: new           #304                // class io/libp2p/core/Libp2pException
      37: dup
      38: aload_1
      39: invokedynamic #568,  0            // InvokeDynamic #5:makeConcatWithConstants:(Lio/libp2p/etc/util/netty/mux/MuxId;)Ljava/lang/String;
      44: invokespecial #569                // Method io/libp2p/core/Libp2pException."<init>":(Ljava/lang/String;)V
      47: athrow
      48: aload_0
      49: getfield      #139                // Field openInboundStreams:I
      52: aload_0
      53: getfield      #14                 // Field maxInboundStreams:I
      56: if_icmplt     77
      59: aload_0
      60: getfield      #143                // Field rejectedInboundStreams:J
      63: lstore_3
      64: aload_0
      65: lload_3
      66: lconst_1
      67: ladd
      68: putfield      #143                // Field rejectedInboundStreams:J
      71: aload_0
      72: aload_1
      73: invokevirtual #572                // Method resetRemoteSubstream:(Lio/libp2p/etc/util/netty/mux/MuxId;)V
      76: return
      77: aload_0
      78: getfield      #139                // Field openInboundStreams:I
      81: istore_3
      82: aload_0
      83: iload_3
      84: iconst_1
      85: iadd
      86: putfield      #139                // Field openInboundStreams:I
      89: aload_0
      90: aload_1
      91: aload_2
      92: iconst_0
      93: invokespecial #576                // Method createChild:(Lio/libp2p/etc/util/netty/mux/MuxId;Lkotlin/jvm/functions/Function1;Z)Lio/libp2p/etc/util/netty/mux/MuxChannel;
      96: astore_3
      97: aload_0
      98: aload_3
      99: invokevirtual #579                // Method onRemoteCreated:(Lio/libp2p/etc/util/netty/mux/MuxChannel;)V
     102: return

  protected void resetRemoteSubstream(io.libp2p.etc.util.netty.mux.MuxId);
    Code:
       0: aload_1
       1: ldc           #126                // String id
       3: invokestatic  #132                // Method kotlin/jvm/internal/Intrinsics.checkNotNullParameter:(Ljava/lang/Object;Ljava/lang/String;)V
       6: return

  protected final void onRemoteDisconnect(io.libp2p.etc.util.netty.mux.MuxId);
    Code:
       0: aload_1
       1: ldc           #126                // String id
       3: invokestatic  #132                // Method kotlin/jvm/internal/Intrinsics.checkNotNullParameter:(Ljava/lang/Object;Ljava/lang/String;)V
       6: aload_0
       7: getfield      #23                 // Field streamMap:Ljava/util/Map;
      10: aload_1
      11: invokeinterface #325,  2          // InterfaceMethod java/util/Map.get:(Ljava/lang/Object;)Ljava/lang/Object;
      16: checkcast     #327                // class io/libp2p/etc/util/netty/mux/MuxChannel
      19: dup
      20: ifnonnull     25
      23: pop
      24: return
      25: astore_2
      26: aload_0
      27: getfield      #52                 // Field pendingInbound:Ljava/util/Map;
      30: aload_1
      31: invokeinterface #325,  2          // InterfaceMethod java/util/Map.get:(Ljava/lang/Object;)Ljava/lang/Object;
      36: checkcast     #236                // class io/libp2p/etc/util/netty/mux/AbstractMuxHandler$PendingInbound
      39: astore_3
      40: aload_2
      41: invokevirtual #343                // Method io/libp2p/etc/util/netty/mux/MuxChannel.getRemoteDisconnected:()Z
      44: ifne          72
      47: aload_3
      48: dup
      49: ifnull        67
      52: invokevirtual #346                // Method io/libp2p/etc/util/netty/mux/AbstractMuxHandler$PendingInbound.getRemoteEndPending:()Z
      55: iconst_1
      56: if_icmpne     63
      59: iconst_1
      60: goto          69
      63: iconst_0
      64: goto          69
      67: pop
      68: iconst_0
      69: ifeq          73
      72: return
      73: aload_3
      74: ifnull        102
      77: aload_3
      78: iconst_1
      79: invokevirtual #587                // Method io/libp2p/etc/util/netty/mux/AbstractMuxHandler$PendingInbound.setRemoteEndPending:(Z)V
      82: aload_2
      83: invokevirtual #353                // Method io/libp2p/etc/util/netty/mux/MuxChannel.config:()Lio/netty/channel/ChannelConfig;
      86: invokeinterface #358,  1          // InterfaceMethod io/netty/channel/ChannelConfig.isAutoRead:()Z
      91: ifeq          106
      94: aload_0
      95: aload_2
      96: invokespecial #365                // Method drainPendingInbound:(Lio/libp2p/etc/util/netty/mux/MuxChannel;)V
      99: goto          106
     102: aload_2
     103: invokevirtual #532                // Method io/libp2p/etc/util/netty/mux/MuxChannel.onRemoteDisconnected:()V
     106: return

  protected final void onRemoteClose(io.libp2p.etc.util.netty.mux.MuxId);
    Code:
       0: aload_1
       1: ldc           #126                // String id
       3: invokestatic  #132                // Method kotlin/jvm/internal/Intrinsics.checkNotNullParameter:(Ljava/lang/Object;Ljava/lang/String;)V
       6: aload_0
       7: getfield      #23                 // Field streamMap:Ljava/util/Map;
      10: aload_1
      11: invokeinterface #325,  2          // InterfaceMethod java/util/Map.get:(Ljava/lang/Object;)Ljava/lang/Object;
      16: checkcast     #327                // class io/libp2p/etc/util/netty/mux/MuxChannel
      19: dup
      20: ifnull        29
      23: invokevirtual #591                // Method io/libp2p/etc/util/netty/mux/MuxChannel.closeImpl:()V
      26: goto          30
      29: pop
      30: return

  public final void localDisconnect(io.libp2p.etc.util.netty.mux.MuxChannel<TData>);
    Code:
       0: aload_1
       1: ldc_w         #453                // String child
       4: invokestatic  #132                // Method kotlin/jvm/internal/Intrinsics.checkNotNullParameter:(Ljava/lang/Object;Ljava/lang/String;)V
       7: aload_0
       8: aload_1
       9: invokevirtual #595                // Method onLocalDisconnect:(Lio/libp2p/etc/util/netty/mux/MuxChannel;)V
      12: return

  public final void localClose(io.libp2p.etc.util.netty.mux.MuxChannel<TData>);
    Code:
       0: aload_1
       1: ldc_w         #453                // String child
       4: invokestatic  #132                // Method kotlin/jvm/internal/Intrinsics.checkNotNullParameter:(Ljava/lang/Object;Ljava/lang/String;)V
       7: aload_0
       8: aload_1
       9: invokevirtual #599                // Method onLocalClose:(Lio/libp2p/etc/util/netty/mux/MuxChannel;)V
      12: return

  public final void onClosed(io.libp2p.etc.util.netty.mux.MuxChannel<TData>);
    Code:
       0: aload_1
       1: ldc_w         #453                // String child
       4: invokestatic  #132                // Method kotlin/jvm/internal/Intrinsics.checkNotNullParameter:(Ljava/lang/Object;Ljava/lang/String;)V
       7: aload_0
       8: getfield      #52                 // Field pendingInbound:Ljava/util/Map;
      11: aload_1
      12: invokevirtual #496                // Method io/libp2p/etc/util/netty/mux/MuxChannel.getId:()Lio/libp2p/etc/util/netty/mux/MuxId;
      15: invokeinterface #410,  2          // InterfaceMethod java/util/Map.remove:(Ljava/lang/Object;)Ljava/lang/Object;
      20: checkcast     #236                // class io/libp2p/etc/util/netty/mux/AbstractMuxHandler$PendingInbound
      23: dup
      24: ifnull        90
      27: invokevirtual #240                // Method io/libp2p/etc/util/netty/mux/AbstractMuxHandler$PendingInbound.getMessages:()Ljava/util/ArrayDeque;
      30: dup
      31: ifnull        90
      34: checkcast     #212                // class java/lang/Iterable
      37: astore        4
      39: iconst_0
      40: istore        5
      42: aload         4
      44: invokeinterface #225,  1          // InterfaceMethod java/lang/Iterable.iterator:()Ljava/util/Iterator;
      49: astore        6
      51: aload         6
      53: invokeinterface #230,  1          // InterfaceMethod java/util/Iterator.hasNext:()Z
      58: ifeq          86
      61: aload         6
      63: invokeinterface #234,  1          // InterfaceMethod java/util/Iterator.next:()Ljava/lang/Object;
      68: astore        7
      70: aload         7
      72: astore        8
      74: iconst_0
      75: istore        9
      77: aload_0
      78: aload         8
      80: invokevirtual #243                // Method releaseMessage:(Ljava/lang/Object;)V
      83: goto          51
      86: nop
      87: goto          92
      90: pop
      91: nop
      92: aload_0
      93: getfield      #62                 // Field parentReadLock:Ljava/lang/Object;
      96: astore_3
      97: aload_3
      98: monitorenter
      99: nop
     100: iconst_0
     101: istore        4
     103: aload_0
     104: getfield      #55                 // Field pausedChildren:Ljava/util/Set;
     107: aload_1
     108: invokevirtual #496                // Method io/libp2p/etc/util/netty/mux/MuxChannel.getId:()Lio/libp2p/etc/util/netty/mux/MuxId;
     111: invokeinterface #602,  2          // InterfaceMethod java/util/Set.remove:(Ljava/lang/Object;)Z
     116: pop
     117: aload_0
     118: invokespecial #604                // Method restoreParentReadsIfPossible:()V
     121: nop
     122: getstatic     #254                // Field kotlin/Unit.INSTANCE:Lkotlin/Unit;
     125: astore        4
     127: aload_3
     128: monitorexit
     129: goto          139
     132: astore        4
     134: aload_3
     135: monitorexit
     136: aload         4
     138: athrow
     139: aload_0
     140: getfield      #23                 // Field streamMap:Ljava/util/Map;
     143: aload_1
     144: invokevirtual #496                // Method io/libp2p/etc/util/netty/mux/MuxChannel.getId:()Lio/libp2p/etc/util/netty/mux/MuxId;
     147: invokeinterface #410,  2          // InterfaceMethod java/util/Map.remove:(Ljava/lang/Object;)Ljava/lang/Object;
     152: ifnull        174
     155: aload_1
     156: invokevirtual #607                // Method io/libp2p/etc/util/netty/mux/MuxChannel.getInitiator:()Z
     159: ifne          174
     162: aload_0
     163: getfield      #139                // Field openInboundStreams:I
     166: istore_2
     167: aload_0
     168: iload_2
     169: iconst_m1
     170: iadd
     171: putfield      #139                // Field openInboundStreams:I
     174: aload_0
     175: aload_1
     176: invokevirtual #610                // Method onChildClosed:(Lio/libp2p/etc/util/netty/mux/MuxChannel;)V
     179: return
    Exception table:
       from    to  target type
          99   127   132   any
         132   134   132   any

  public abstract void channelRead(io.netty.channel.ChannelHandlerContext, java.lang.Object);

  protected void onRemoteCreated(io.libp2p.etc.util.netty.mux.MuxChannel<TData>);
    Code:
       0: aload_1
       1: ldc_w         #453                // String child
       4: invokestatic  #132                // Method kotlin/jvm/internal/Intrinsics.checkNotNullParameter:(Ljava/lang/Object;Ljava/lang/String;)V
       7: return

  protected abstract void onLocalOpen(io.libp2p.etc.util.netty.mux.MuxChannel<TData>);

  protected abstract void onLocalClose(io.libp2p.etc.util.netty.mux.MuxChannel<TData>);

  protected abstract void onLocalDisconnect(io.libp2p.etc.util.netty.mux.MuxChannel<TData>);

  protected abstract void onChildClosed(io.libp2p.etc.util.netty.mux.MuxChannel<TData>);

  private final io.libp2p.etc.util.netty.mux.MuxChannel<TData> createChild(io.libp2p.etc.util.netty.mux.MuxId, kotlin.jvm.functions.Function1<? super io.libp2p.etc.util.netty.mux.MuxChannel<TData>, kotlin.Unit>, boolean);
    Code:
       0: new           #327                // class io/libp2p/etc/util/netty/mux/MuxChannel
       3: dup
       4: aload_0
       5: aload_1
       6: aload_2
       7: iload_3
       8: invokespecial #619                // Method io/libp2p/etc/util/netty/mux/MuxChannel."<init>":(Lio/libp2p/etc/util/netty/mux/AbstractMuxHandler;Lio/libp2p/etc/util/netty/mux/MuxId;Lkotlin/jvm/functions/Function1;Z)V
      11: astore        4
      13: aload_0
      14: getfield      #23                 // Field streamMap:Ljava/util/Map;
      17: aload_1
      18: aload         4
      20: invokeinterface #374,  3          // InterfaceMethod java/util/Map.put:(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
      25: pop
      26: aload_0
      27: getfield      #94                 // Field ctx:Lio/netty/channel/ChannelHandlerContext;
      30: dup
      31: invokestatic  #622                // Method kotlin/jvm/internal/Intrinsics.checkNotNull:(Ljava/lang/Object;)V
      34: invokeinterface #153,  1          // InterfaceMethod io/netty/channel/ChannelHandlerContext.channel:()Lio/netty/channel/Channel;
      39: invokeinterface #626,  1          // InterfaceMethod io/netty/channel/Channel.eventLoop:()Lio/netty/channel/EventLoop;
      44: aload         4
      46: checkcast     #155                // class io/netty/channel/Channel
      49: invokeinterface #632,  2          // InterfaceMethod io/netty/channel/EventLoop.register:(Lio/netty/channel/Channel;)Lio/netty/channel/ChannelFuture;
      54: invokeinterface #635,  1          // InterfaceMethod io/netty/channel/ChannelFuture.sync:()Lio/netty/channel/ChannelFuture;
      59: pop
      60: aload         4
      62: areturn

  protected abstract io.libp2p.etc.util.netty.mux.MuxId generateNextId();

  public final java.util.concurrent.CompletableFuture<io.libp2p.etc.util.netty.mux.MuxChannel<TData>> newStream(kotlin.jvm.functions.Function1<? super io.libp2p.etc.util.netty.mux.MuxChannel<TData>, kotlin.Unit>);
    Code:
       0: aload_1
       1: ldc_w         #644                // String outboundInitializer
       4: invokestatic  #132                // Method kotlin/jvm/internal/Intrinsics.checkNotNullParameter:(Ljava/lang/Object;Ljava/lang/String;)V
       7: new           #25                 // class java/util/concurrent/CompletableFuture
      10: dup
      11: invokespecial #26                 // Method java/util/concurrent/CompletableFuture."<init>":()V
      14: astore_2
      15: nop
      16: aload_0
      17: invokespecial #647                // Method checkClosed:()V
      20: aload_0
      21: getfield      #40                 // Field pendingStreams:Ljava/util/concurrent/ConcurrentHashMap$KeySetView;
      24: aload_2
      25: invokevirtual #650                // Method java/util/concurrent/ConcurrentHashMap$KeySetView.add:(Ljava/lang/Object;)Z
      28: pop
      29: aload_2
      30: aload_0
      31: aload_2
      32: invokedynamic #662,  0            // InvokeDynamic #6:accept:(Lio/libp2p/etc/util/netty/mux/AbstractMuxHandler;Ljava/util/concurrent/CompletableFuture;)Ljava/util/function/BiConsumer;
      37: invokevirtual #666                // Method java/util/concurrent/CompletableFuture.whenComplete:(Ljava/util/function/BiConsumer;)Ljava/util/concurrent/CompletableFuture;
      40: pop
      41: aload_0
      42: invokespecial #647                // Method checkClosed:()V
      45: aload_0
      46: getfield      #30                 // Field activeFuture:Ljava/util/concurrent/CompletableFuture;
      49: aload_2
      50: aload_0
      51: aload_1
      52: invokedynamic #674,  0            // InvokeDynamic #7:run:(Ljava/util/concurrent/CompletableFuture;Lio/libp2p/etc/util/netty/mux/AbstractMuxHandler;Lkotlin/jvm/functions/Function1;)Ljava/lang/Runnable;
      57: aload_0
      58: invokevirtual #460                // Method getChannelHandlerContext:()Lio/netty/channel/ChannelHandlerContext;
      61: invokeinterface #153,  1          // InterfaceMethod io/netty/channel/ChannelHandlerContext.channel:()Lio/netty/channel/Channel;
      66: invokeinterface #626,  1          // InterfaceMethod io/netty/channel/Channel.eventLoop:()Lio/netty/channel/EventLoop;
      71: checkcast     #676                // class java/util/concurrent/Executor
      74: invokevirtual #680                // Method java/util/concurrent/CompletableFuture.thenRunAsync:(Ljava/lang/Runnable;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;
      77: dup
      78: ldc_w         #682                // String activeFuture.thenRunAsyn…eventLoop()\n            )
      81: invokestatic  #685                // Method kotlin/jvm/internal/Intrinsics.checkNotNullExpressionValue:(Ljava/lang/Object;Ljava/lang/String;)V
      84: aload_2
      85: invokestatic  #691                // Method io/libp2p/etc/types/AsyncExtKt.forwardException:(Ljava/util/concurrent/CompletableFuture;Ljava/util/concurrent/CompletableFuture;)Ljava/util/concurrent/CompletableFuture;
      88: pop
      89: goto          102
      92: astore_3
      93: aload_2
      94: aload_3
      95: checkcast     #195                // class java/lang/Throwable
      98: invokevirtual #199                // Method java/util/concurrent/CompletableFuture.completeExceptionally:(Ljava/lang/Throwable;)Z
     101: pop
     102: aload_2
     103: areturn
    Exception table:
       from    to  target type
          15    89    92   Class java/lang/Exception

  private final void checkClosed();
    Code:
       0: aload_0
       1: getfield      #187                // Field closed:Z
       4: ifne          45
       7: aload_0
       8: getfield      #94                 // Field ctx:Lio/netty/channel/ChannelHandlerContext;
      11: dup
      12: ifnull        40
      15: invokeinterface #153,  1          // InterfaceMethod io/netty/channel/ChannelHandlerContext.channel:()Lio/netty/channel/Channel;
      20: dup
      21: ifnull        40
      24: invokeinterface #190,  1          // InterfaceMethod io/netty/channel/Channel.isOpen:()Z
      29: ifne          36
      32: iconst_1
      33: goto          42
      36: iconst_0
      37: goto          42
      40: pop
      41: iconst_0
      42: ifeq          71
      45: new           #192                // class io/libp2p/core/ConnectionClosedException
      48: dup
      49: aload_0
      50: getfield      #94                 // Field ctx:Lio/netty/channel/ChannelHandlerContext;
      53: dup
      54: invokestatic  #622                // Method kotlin/jvm/internal/Intrinsics.checkNotNull:(Ljava/lang/Object;)V
      57: invokeinterface #153,  1          // InterfaceMethod io/netty/channel/ChannelHandlerContext.channel:()Lio/netty/channel/Channel;
      62: invokedynamic #699,  0            // InvokeDynamic #8:makeConcatWithConstants:(Lio/netty/channel/Channel;)Ljava/lang/String;
      67: invokespecial #340                // Method io/libp2p/core/ConnectionClosedException."<init>":(Ljava/lang/String;)V
      70: athrow
      71: nop
      72: return

  private static final void handlerAdded$lambda-1(io.libp2p.etc.util.netty.mux.AbstractMuxHandler, io.netty.util.concurrent.Future);
    Code:
       0: aload_0
       1: ldc_w         #701                // String this$0
       4: invokestatic  #132                // Method kotlin/jvm/internal/Intrinsics.checkNotNullParameter:(Ljava/lang/Object;Ljava/lang/String;)V
       7: new           #192                // class io/libp2p/core/ConnectionClosedException
      10: dup
      11: invokespecial #193                // Method io/libp2p/core/ConnectionClosedException."<init>":()V
      14: astore_2
      15: aload_0
      16: getfield      #30                 // Field activeFuture:Ljava/util/concurrent/CompletableFuture;
      19: aload_2
      20: checkcast     #195                // class java/lang/Throwable
      23: invokevirtual #199                // Method java/util/concurrent/CompletableFuture.completeExceptionally:(Ljava/lang/Throwable;)Z
      26: pop
      27: aload_0
      28: getfield      #40                 // Field pendingStreams:Ljava/util/concurrent/ConcurrentHashMap$KeySetView;
      31: dup
      32: ldc_w         #702                // String pendingStreams
      35: invokestatic  #685                // Method kotlin/jvm/internal/Intrinsics.checkNotNullExpressionValue:(Ljava/lang/Object;Ljava/lang/String;)V
      38: checkcast     #212                // class java/lang/Iterable
      41: astore_3
      42: iconst_0
      43: istore        4
      45: aload_3
      46: invokeinterface #225,  1          // InterfaceMethod java/lang/Iterable.iterator:()Ljava/util/Iterator;
      51: astore        5
      53: aload         5
      55: invokeinterface #230,  1          // InterfaceMethod java/util/Iterator.hasNext:()Z
      60: ifeq          95
      63: aload         5
      65: invokeinterface #234,  1          // InterfaceMethod java/util/Iterator.next:()Ljava/lang/Object;
      70: astore        6
      72: aload         6
      74: checkcast     #25                 // class java/util/concurrent/CompletableFuture
      77: astore        7
      79: iconst_0
      80: istore        8
      82: aload         7
      84: aload_2
      85: checkcast     #195                // class java/lang/Throwable
      88: invokevirtual #199                // Method java/util/concurrent/CompletableFuture.completeExceptionally:(Ljava/lang/Throwable;)Z
      91: pop
      92: goto          53
      95: nop
      96: return

  private static final void childAutoReadChanged$lambda-6(io.libp2p.etc.util.netty.mux.MuxChannel, io.libp2p.etc.util.netty.mux.AbstractMuxHandler);
    Code:
       0: aload_0
       1: ldc_w         #708                // String $child
       4: invokestatic  #132                // Method kotlin/jvm/internal/Intrinsics.checkNotNullParameter:(Ljava/lang/Object;Ljava/lang/String;)V
       7: aload_1
       8: ldc_w         #701                // String this$0
      11: invokestatic  #132                // Method kotlin/jvm/internal/Intrinsics.checkNotNullParameter:(Ljava/lang/Object;Ljava/lang/String;)V
      14: aload_0
      15: invokevirtual #366                // Method io/libp2p/etc/util/netty/mux/MuxChannel.isOpen:()Z
      18: ifeq          105
      21: aload_0
      22: invokevirtual #353                // Method io/libp2p/etc/util/netty/mux/MuxChannel.config:()Lio/netty/channel/ChannelConfig;
      25: invokeinterface #358,  1          // InterfaceMethod io/netty/channel/ChannelConfig.isAutoRead:()Z
      30: ifeq          100
      33: aload_1
      34: aload_0
      35: invokespecial #365                // Method drainPendingInbound:(Lio/libp2p/etc/util/netty/mux/MuxChannel;)V
      38: aload_1
      39: getfield      #62                 // Field parentReadLock:Ljava/lang/Object;
      42: astore_2
      43: aload_2
      44: monitorenter
      45: nop
      46: iconst_0
      47: istore_3
      48: aload_0
      49: invokevirtual #366                // Method io/libp2p/etc/util/netty/mux/MuxChannel.isOpen:()Z
      52: ifeq          85
      55: aload_0
      56: invokevirtual #353                // Method io/libp2p/etc/util/netty/mux/MuxChannel.config:()Lio/netty/channel/ChannelConfig;
      59: invokeinterface #358,  1          // InterfaceMethod io/netty/channel/ChannelConfig.isAutoRead:()Z
      64: ifeq          85
      67: aload_1
      68: getfield      #55                 // Field pausedChildren:Ljava/util/Set;
      71: aload_0
      72: invokevirtual #496                // Method io/libp2p/etc/util/netty/mux/MuxChannel.getId:()Lio/libp2p/etc/util/netty/mux/MuxId;
      75: invokeinterface #602,  2          // InterfaceMethod java/util/Set.remove:(Ljava/lang/Object;)Z
      80: pop
      81: aload_1
      82: invokespecial #604                // Method restoreParentReadsIfPossible:()V
      85: nop
      86: getstatic     #254                // Field kotlin/Unit.INSTANCE:Lkotlin/Unit;
      89: astore_3
      90: aload_2
      91: monitorexit
      92: goto          105
      95: astore_3
      96: aload_2
      97: monitorexit
      98: aload_3
      99: athrow
     100: aload_1
     101: aload_0
     102: invokespecial #369                // Method pauseChild:(Lio/libp2p/etc/util/netty/mux/MuxChannel;)V
     105: return
    Exception table:
       from    to  target type
          45    90    95   any
          95    96    95   any

  private static final void newStream$lambda-11(io.libp2p.etc.util.netty.mux.AbstractMuxHandler, java.util.concurrent.CompletableFuture, io.libp2p.etc.util.netty.mux.MuxChannel, java.lang.Throwable);
    Code:
       0: aload_0
       1: ldc_w         #701                // String this$0
       4: invokestatic  #132                // Method kotlin/jvm/internal/Intrinsics.checkNotNullParameter:(Ljava/lang/Object;Ljava/lang/String;)V
       7: aload_1
       8: ldc_w         #711                // String $result
      11: invokestatic  #132                // Method kotlin/jvm/internal/Intrinsics.checkNotNullParameter:(Ljava/lang/Object;Ljava/lang/String;)V
      14: aload_0
      15: getfield      #40                 // Field pendingStreams:Ljava/util/concurrent/ConcurrentHashMap$KeySetView;
      18: aload_1
      19: invokevirtual #712                // Method java/util/concurrent/ConcurrentHashMap$KeySetView.remove:(Ljava/lang/Object;)Z
      22: pop
      23: return

  private static final void newStream$lambda-12(java.util.concurrent.CompletableFuture, io.libp2p.etc.util.netty.mux.AbstractMuxHandler, kotlin.jvm.functions.Function1);
    Code:
       0: aload_0
       1: ldc_w         #711                // String $result
       4: invokestatic  #132                // Method kotlin/jvm/internal/Intrinsics.checkNotNullParameter:(Ljava/lang/Object;Ljava/lang/String;)V
       7: aload_1
       8: ldc_w         #701                // String this$0
      11: invokestatic  #132                // Method kotlin/jvm/internal/Intrinsics.checkNotNullParameter:(Ljava/lang/Object;Ljava/lang/String;)V
      14: aload_2
      15: ldc_w         #714                // String $outboundInitializer
      18: invokestatic  #132                // Method kotlin/jvm/internal/Intrinsics.checkNotNullParameter:(Ljava/lang/Object;Ljava/lang/String;)V
      21: aload_0
      22: invokevirtual #717                // Method java/util/concurrent/CompletableFuture.isDone:()Z
      25: ifne          60
      28: aload_1
      29: invokespecial #647                // Method checkClosed:()V
      32: aload_1
      33: aload_1
      34: invokevirtual #719                // Method generateNextId:()Lio/libp2p/etc/util/netty/mux/MuxId;
      37: new           #721                // class io/libp2p/etc/util/netty/mux/AbstractMuxHandler$newStream$2$child$1
      40: dup
      41: aload_1
      42: aload_2
      43: invokespecial #724                // Method io/libp2p/etc/util/netty/mux/AbstractMuxHandler$newStream$2$child$1."<init>":(Lio/libp2p/etc/util/netty/mux/AbstractMuxHandler;Lkotlin/jvm/functions/Function1;)V
      46: checkcast     #583                // class kotlin/jvm/functions/Function1
      49: iconst_1
      50: invokespecial #576                // Method createChild:(Lio/libp2p/etc/util/netty/mux/MuxId;Lkotlin/jvm/functions/Function1;Z)Lio/libp2p/etc/util/netty/mux/MuxChannel;
      53: astore_3
      54: aload_0
      55: aload_3
      56: invokevirtual #203                // Method java/util/concurrent/CompletableFuture.complete:(Ljava/lang/Object;)Z
      59: pop
      60: return

  public io.libp2p.etc.util.netty.mux.AbstractMuxHandler();
    Code:
       0: aload_0
       1: iconst_0
       2: iconst_1
       3: aconst_null
       4: invokespecial #726                // Method "<init>":(IILkotlin/jvm/internal/DefaultConstructorMarker;)V
       7: return
}

```
