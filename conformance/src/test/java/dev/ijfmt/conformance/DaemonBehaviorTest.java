package dev.ijfmt.conformance;

import dev.ijfmt.cli.Client;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Behaviour of the long-lived daemon that a one-shot differential run cannot observe. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DaemonBehaviorTest {
  private static final String UNFORMATTED = "class A {\nvoid m(){int x=1;\nif(x>0){x++;}}\n}\n";

  private Client client;

  @BeforeAll
  void start() throws IOException {
    Settings settings = Settings.fromSystemProperties();
    client = Client.start(settings.ideHome, settings.pluginDir, settings.stateRoot.resolve("daemon"),
                          settings.stateRoot.resolve("no-config.properties"), Duration.ofMinutes(3));
  }

  @AfterAll
  void stop() {
    if (client != null) client.close();
  }

  private String format(Path file, String text) throws IOException {
    Client.Result result = client.formatText(file, text.getBytes(StandardCharsets.UTF_8));
    assertEquals(200, result.status(), result.text());
    return result.text();
  }

  private static final java.util.concurrent.atomic.AtomicLong CLOCK = new java.util.concurrent.atomic.AtomicLong();

  private static void write(Path file, String text) throws IOException {
    Files.createDirectories(file.getParent());
    Files.writeString(file, text);
    // Rewrites of same-sized files must still be seen as changes, even within the file system's timestamp granularity.
    Files.setLastModifiedTime(file, FileTime.from(Instant.now().plusSeconds(CLOCK.incrementAndGet())));
  }

  @Test
  void bufferModeNeverWritesToDisk(@TempDir Path dir) throws IOException {
    write(dir.resolve(".editorconfig"), "root = true\n");
    Path file = dir.resolve("A.java");
    write(file, UNFORMATTED);
    byte[] before = Files.readAllBytes(file);
    String formatted = format(file, UNFORMATTED);
    assertTrue(formatted.contains("    void m() {"), formatted);
    assertArrayEquals(before, Files.readAllBytes(file));
  }

  @Test
  void picksUpEditorConfigChangesBetweenRequests(@TempDir Path dir) throws IOException {
    Path file = dir.resolve("src").resolve("A.java");
    write(file, UNFORMATTED);
    write(dir.resolve(".editorconfig"), "root = true\n[*.java]\nindent_size = 2\n");
    assertTrue(format(file, UNFORMATTED).contains("\n  void m() {"));
    write(dir.resolve(".editorconfig"), "root = true\n[*.java]\nindent_size = 8\n");
    assertTrue(format(file, UNFORMATTED).contains("\n        void m() {"));
    // A new .editorconfig closer to the file
    write(dir.resolve("src").resolve(".editorconfig"), "[*.java]\nindent_size = 3\n");
    assertTrue(format(file, UNFORMATTED).contains("\n   void m() {"));
    Files.delete(dir.resolve("src").resolve(".editorconfig"));
    assertTrue(format(file, UNFORMATTED).contains("\n        void m() {"));
  }

  @Test
  void picksUpProjectCodeStyleChanges(@TempDir Path dir) throws IOException {
    write(dir.resolve(".editorconfig"), "root = true\n");
    Path file = dir.resolve("A.java");
    write(file, UNFORMATTED);
    write(dir.resolve(".idea/codeStyles/codeStyleConfig.xml"),
          "<component name=\"ProjectCodeStyleConfiguration\"><state>"
          + "<option name=\"USE_PER_PROJECT_SETTINGS\" value=\"true\" /></state></component>\n");
    String scheme = "<component name=\"ProjectCodeStyleConfiguration\"><code_scheme name=\"Project\" version=\"173\">"
                    + "<codeStyleSettings language=\"JAVA\"><indentOptions><option name=\"INDENT_SIZE\" value=\"%d\" />"
                    + "</indentOptions></codeStyleSettings></code_scheme></component>\n";
    write(dir.resolve(".idea/codeStyles/Project.xml"), String.format(scheme, 6));
    String six = format(file, UNFORMATTED);
    assertTrue(six.contains("\n      void m() {"), six);
    write(dir.resolve(".idea/codeStyles/Project.xml"), String.format(scheme, 1));
    String one = format(file, UNFORMATTED);
    assertTrue(one.contains("\n void m() {"), one);
  }

  @Test
  void formatsUnsavedNewFilesUsingTheirFutureLocation(@TempDir Path dir) throws IOException {
    write(dir.resolve(".editorconfig"), "root = true\n[*.java]\nindent_size = 3\ninsert_final_newline = true\n");
    Path file = dir.resolve("not").resolve("created").resolve("yet").resolve("New.java");
    String formatted = format(file, "class New {\nvoid m(){}}");
    assertEquals("class New {\n   void m() {\n   }\n}\n", formatted);
    assertTrue(Files.notExists(file));
  }

  @Test
  void lineRangesOnlyTouchSelectedLines(@TempDir Path dir) throws IOException {
    write(dir.resolve(".editorconfig"), "root = true\n");
    Path file = dir.resolve("A.java");
    String input = "class A {\nint a=1;\nint b=2;\nint c=3;\n}\n";
    write(file, input);
    Client.Result result = client.formatText(file, input.getBytes(StandardCharsets.UTF_8), "3:3");
    assertEquals(200, result.status(), result.text());
    assertEquals("class A {\nint a=1;\n    int b = 2;\nint c=3;\n}\n", result.text());
  }

  @Test
  void keepsTheInputsLineSeparators(@TempDir Path dir) throws IOException {
    write(dir.resolve(".editorconfig"), "root = true\n");
    Path file = dir.resolve("A.java");
    write(file, UNFORMATTED);
    String formatted = format(file, UNFORMATTED.replace("\n", "\r\n"));
    assertTrue(formatted.contains("\r\n    void m() {\r\n"), formatted);
    assertEquals(formatted.split("\n", -1).length - 1, formatted.split("\r\n", -1).length - 1, "only CRLF separators");
  }

  @Test
  void reportsUnsupportedFileTypes(@TempDir Path dir) throws IOException {
    Client.Result result = client.formatText(dir.resolve("notes.unknown-extension"), "a  b".getBytes(StandardCharsets.UTF_8));
    assertEquals(415, result.status(), result.text());
  }

  @Test
  void formatsInPlaceAndReportsChanges(@TempDir Path dir) throws IOException {
    write(dir.resolve(".editorconfig"), "root = true\n");
    Path file = dir.resolve("A.java");
    write(file, UNFORMATTED);
    assertEquals("{\"changed\":true}", client.checkFile(file).text());
    assertArrayEquals(UNFORMATTED.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(file), "check must not write");
    assertEquals("{\"changed\":true}", client.formatFile(file).text());
    assertEquals("{\"changed\":false}", client.checkFile(file).text());
    assertEquals("{\"changed\":false}", client.formatFile(file).text());
  }
}
