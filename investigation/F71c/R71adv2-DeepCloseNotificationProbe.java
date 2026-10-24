import io.libp2p.core.*;
import io.libp2p.core.crypto.KeyType;
import io.libp2p.core.dsl.HostBuilder;
import io.libp2p.transport.implementation.ConnectionOverNetty;
import io.netty.channel.*;
import io.netty.util.concurrent.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class DeepCloseNotificationProbe {
 public static void main(String[] args) throws Exception {
  Host server=new HostBuilder().keyType(KeyType.ED25519).listen("/ip4/127.0.0.1/tcp/0").build();
  Host client=new HostBuilder().keyType(KeyType.ED25519).build();
  CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),drained=new CountDownLatch(2048);
  CompletableFuture<CompletableFuture<kotlin.Unit>> observed=new CompletableFuture<>();
  try {
   server.start().get(5,TimeUnit.SECONDS); client.start().get(5,TimeUnit.SECONDS);
   ConnectionOverNetty conn=(ConnectionOverNetty)client.getNetwork().connect(server.getPeerId(),server.listenAddresses().toArray(new io.libp2p.core.multiformats.Multiaddr[0])).get(5,TimeUnit.SECONDS);
   EventLoop loop=conn.getNettyChannel().eventLoop();
   server.stop().get(5,TimeUnit.SECONDS); conn.closeFuture().get(5,TimeUnit.SECONDS); loop.submit(()->{}).get(5,TimeUnit.SECONDS);
   loop.execute(()->{
    entered.countDown();
    try {
     if(!release.await(10,TimeUnit.SECONDS)) throw new AssertionError("Release missing");
     @SuppressWarnings("unchecked") Promise<Void>[] chain=new Promise[8];
     for(int i=0;i<chain.length;i++) chain[i]=new DefaultPromise<>(loop);
     for(int i=0;i<chain.length-1;i++){ final int next=i+1;chain[i].addListener(f->chain[next].setSuccess(null)); }
     chain[chain.length-1].addListener(f->observed.complete(conn.close()));
     chain[0].setSuccess(null);
    }catch(Throwable failure){observed.completeExceptionally(failure);}
   });
   if(!entered.await(5,TimeUnit.SECONDS)) throw new AssertionError("Loop not held");
   for(int i=0;i<2048;i++)loop.execute(drained::countDown);
   try{loop.execute(()->{});throw new AssertionError("Loop must reject");}catch(RejectedExecutionException expected){}
   release.countDown();
   CompletableFuture<kotlin.Unit> closing=observed.get(5,TimeUnit.SECONDS);
   if(!drained.await(5,TimeUnit.SECONDS))throw new AssertionError("Queue not drained");
   loop.submit(()->{}).get(5,TimeUnit.SECONDS);
   System.out.println("physicalCloseDone="+conn.getNettyChannel().closeFuture().isDone()+" closeOperationDoneAfterRecovery="+closing.isDone()+" loopShuttingDown="+loop.isShuttingDown());
   if(closing.isDone())throw new AssertionError("Expected depth-limited listener rejection to leave close pending");
  }finally{
   release.countDown();
   // Failed observation intentionally remains pending. Both physical channels are closed already.
   try { CompletableFuture.allOf(client.stop(),server.stop()).get(1,TimeUnit.SECONDS); }
   catch(TimeoutException expected){System.out.println("hostStopAlsoPendingAfterLostCloseNotification=true");}
  }
 }
}
