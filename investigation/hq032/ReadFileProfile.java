import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import jdk.jfr.consumer.RecordingFile;
class ReadFileProfile {
  public static void main(String[] args) throws Exception {
    Map<String,long[]> totals = new HashMap<>();
    long events = 0, bytes = 0;
    try (RecordingFile input = new RecordingFile(Path.of(args[0]));
         var csv = new java.io.BufferedWriter(new java.io.OutputStreamWriter(
             new java.util.zip.GZIPOutputStream(java.nio.file.Files.newOutputStream(Path.of(args[1]))),
             java.nio.charset.StandardCharsets.UTF_8))) {
      csv.write("start,bytes,path\n");
      while (input.hasMoreEvents()) {
        var event = input.readEvent();
        if (!event.getEventType().getName().equals("jdk.FileRead")) continue;
        var path = String.valueOf(event.getString("path"));
        var read = event.getLong("bytesRead");
        csv.write(event.getStartTime() + "," + read + ",\"" + path.replace("\"", "\"\"") + "\"\n");
        var row = totals.computeIfAbsent(path, key -> new long[2]);
        row[0]++; row[1] += read; events++; bytes += read;
      }
    }
    System.out.println("30-second preparation profile: events=" + events + " bytes=" + bytes);
    totals.entrySet().stream().sorted((a,b) -> Long.compare(b.getValue()[1], a.getValue()[1])).limit(15).forEach(e ->
      System.out.println(e.getValue()[1] + " bytes; " + e.getValue()[0] + " reads; " + e.getKey()));
  }
}
