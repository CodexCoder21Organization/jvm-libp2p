import java.lang.instrument.*;
import java.util.concurrent.*;
import java.util.*;
import jdk.internal.org.objectweb.asm.*;
public class QueueTrace {
  static final Map<Object,Map<Runnable,String>> queued=new ConcurrentHashMap<>();
  public static void remember(Object loop,Runnable task) {
    String context=Arrays.stream(Thread.currentThread().getStackTrace()).map(x->x.getClassName()+"."+x.getMethodName()+":"+x.getLineNumber()).reduce((a,b)->a+";"+b).orElse("");
    queued.computeIfAbsent(loop,k->new ConcurrentHashMap<>()).put(task,task.getClass().getName()+" context="+context);
  }
  public static void taken(Runnable task) { if(task!=null) queued.values().forEach(m->m.remove(task)); }
  public static void pending(int count,Object loop) {
    if(count>0) System.err.println("NOISE3 queue count="+count+" thread="+Thread.currentThread().getName()+" entries="+queued.get(loop));
  }
  public static void premain(String args,Instrumentation api) {
    api.addTransformer(new ClassFileTransformer() {
      public byte[] transform(ClassLoader loader,String name,Class<?> type,java.security.ProtectionDomain domain,byte[] bytes) {
        if(!name.equals("io/netty/util/concurrent/SingleThreadEventExecutor")) return null;
        ClassReader reader=new ClassReader(bytes); ClassWriter writer=new ClassWriter(reader,ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM8,writer) {
          public MethodVisitor visitMethod(int access,String method,String desc,String signature,String[] exceptions) {
            MethodVisitor base=super.visitMethod(access,method,desc,signature,exceptions);
            return new MethodVisitor(Opcodes.ASM8,base) {
              public void visitCode() {
                super.visitCode();
                if(method.equals("offerTask") && desc.equals("(Ljava/lang/Runnable;)Z")) {
                  visitVarInsn(Opcodes.ALOAD,0);visitVarInsn(Opcodes.ALOAD,1);
                  visitMethodInsn(Opcodes.INVOKESTATIC,"QueueTrace","remember","(Ljava/lang/Object;Ljava/lang/Runnable;)V",false);
                }
              }
              public void visitInsn(int opcode) {
                if(method.equals("pollTaskFrom") && opcode==Opcodes.ARETURN) {
                  visitInsn(Opcodes.DUP);visitMethodInsn(Opcodes.INVOKESTATIC,"QueueTrace","taken","(Ljava/lang/Runnable;)V",false);
                }
                if(method.equals("pendingTasks") && opcode==Opcodes.IRETURN) {
                  visitInsn(Opcodes.DUP);visitVarInsn(Opcodes.ALOAD,0);visitMethodInsn(Opcodes.INVOKESTATIC,"QueueTrace","pending","(ILjava/lang/Object;)V",false);
                }
                super.visitInsn(opcode);
              }
            };
          }
        },0);
        return writer.toByteArray();
      }
    });
  }
}
