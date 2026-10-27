import java.nio.file.*;
import java.util.concurrent.TimeUnit;
public class KuboInputProof {
  public static void main(String[] args) throws Exception {
    Path repo=Files.createTempDirectory(Path.of(args[1]),"stdin-proof-").resolve("node");
    Process p=new ProcessBuilder(args[0],"--repo-dir",repo.toString(),"init","--profile=test").redirectErrorStream(true).start();
    try {
      boolean open=p.waitFor(5,TimeUnit.SECONDS);
      System.out.println("exit with stdin open="+open);
      if(open) {System.out.println("initial exit="+p.exitValue()+" output="+new String(p.getInputStream().readAllBytes())); return;}
      int available=p.getInputStream().available();
      System.out.println("available output bytes="+available);
      System.out.println("partial output="+new String(p.getInputStream().readNBytes(available)));
      boolean drained=p.waitFor(5,TimeUnit.SECONDS);
      System.out.println("exit after draining output="+drained);
      if(drained) {System.out.println("exit="+p.exitValue()+" rest="+new String(p.getInputStream().readAllBytes())); return;}
      p.getOutputStream().close();
      boolean closed=p.waitFor(5,TimeUnit.SECONDS);
      System.out.println("exit after EOF="+closed);
      if(!closed) throw new AssertionError("EOF did not release init");
      System.out.println("exit="+p.exitValue()+" output="+new String(p.getInputStream().readAllBytes()));
      if(p.exitValue()!=0) throw new AssertionError("init failed");
    } finally {if(p.isAlive()) {p.destroyForcibly(); p.waitFor(5,TimeUnit.SECONDS);}}
  }
}
