package dev.ijfmt.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliTest {
  @Test
  void parsesJson() {
    Map<String, Object> json = Json.parseObject(
      "{\"a\": [1, 2.5, -3], \"b\": {\"c\": true, \"d\": null}, \"e\": \"x\\\"y\\\\z\\u00e9\\n\"}");
    assertEquals(List.of(1L, 2.5, -3L), json.get("a"));
    assertEquals(true, ((Map<?, ?>)json.get("b")).get("c"));
    assertTrue(((Map<?, ?>)json.get("b")).containsKey("d"));
    assertEquals("x\"y\\z\u00e9\n", json.get("e"));
    assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\": 1,}"));
    assertThrows(IllegalArgumentException.class, () -> Json.parse("[1] 2"));
  }

  @Test
  void parsesOptions() {
    Main.Options options = Main.Options.parse(new String[]{"--stdin-filepath", "A.java", "--lines=3:5", "--check", "-q"});
    assertEquals("A.java", options.stdinPath);
    assertEquals("3:5", options.lines);
    assertTrue(options.check);
    assertTrue(options.quiet);
    Main.Options files = Main.Options.parse(new String[]{"--mask", "*.java,*.kt", "src", "--", "-weird-name.java"});
    assertEquals("*.java,*.kt", files.mask);
    assertEquals(List.of("src", "-weird-name.java"), files.positional);
    assertThrows(CliException.class, () -> Main.Options.parse(new String[]{"--nope"}));
    assertThrows(CliException.class, () -> Main.Options.parse(new String[]{"--stdin-filepath"}));
  }

  @Test
  void collectsFilesSkippingHiddenDirectories(@TempDir Path dir) throws IOException {
    Files.createDirectories(dir.resolve("src/main"));
    Files.createDirectories(dir.resolve(".git"));
    Files.writeString(dir.resolve("src/main/A.java"), "");
    Files.writeString(dir.resolve("src/main/B.kt"), "");
    Files.writeString(dir.resolve(".git/C.java"), "");
    List<Path> all = Main.collectFiles(dir, List.of());
    assertEquals(List.of(dir.resolve("src/main/A.java"), dir.resolve("src/main/B.kt")), all);
    List<Path> java = Main.collectFiles(dir, List.of(dir.getFileSystem().getPathMatcher("glob:*.java")));
    assertEquals(List.of(dir.resolve("src/main/A.java")), java);
  }

  @Test
  void buildsTheIdeLaunchCommandFromProductInfo(@TempDir Path ide) throws IOException {
    Files.createDirectories(ide.resolve("lib"));
    Files.createDirectories(ide.resolve("bin"));
    Files.writeString(ide.resolve("bin/idea64.vmoptions"), "# comment\n-Xmx2048m\n-ea\n\n");
    String os = Config.isWindows() ? "Windows" : Config.isMac() ? "macOS" : "Linux";
    String arch = System.getProperty("os.arch").equals("aarch64") ? "aarch64" : "amd64";
    Files.writeString(ide.resolve("product-info.json"), "{\"name\":\"IntelliJ IDEA\",\"version\":\"2099.1\",\"buildNumber\":\"991.1.2\","
      + "\"productCode\":\"IU\",\"launch\":[{\"os\":\"" + os + "\",\"arch\":\"" + arch + "\",\"javaExecutablePath\":\"jbr/bin/java\","
      + "\"vmOptionsFilePath\":\"bin/idea64.vmoptions\",\"bootClassPathJarNames\":[\"a.jar\",\"b.jar\"],"
      + "\"additionalJvmArguments\":[\"-Dsplash=true\",\"-Dx=%IDE_HOME%/lib\",\"-Dy=$IDE_HOME/z\"],\"mainClass\":\"com.intellij.idea.Main\"}]}");
    IdeInstallation installation = IdeInstallation.load(ide).orElseThrow();
    assertEquals("IU-991.1.2", installation.id());
    List<String> command = installation.launchCommand(Path.of("plugin"), Path.of("state"), "1g", List.of("ijfmt-server"));
    assertEquals(installation.home.resolve("jbr/bin/java").normalize().toString(), command.get(0));
    assertTrue(command.contains("-ea"));
    assertTrue(command.contains("-Xmx1g"));
    assertFalse(command.contains("-Xmx2048m"), "configured heap replaces the IDE's");
    assertFalse(command.contains("-Dsplash=true"));
    assertTrue(command.contains("-Dx=" + installation.home + "/lib"));
    assertTrue(command.contains("-Dy=" + installation.home + "/z"));
    assertTrue(command.contains("-Dplugin.path=plugin"));
    assertTrue(command.contains("-Didea.config.path=" + Path.of("state", "config")));
    String classpath = command.get(command.indexOf("-cp") + 1);
    assertTrue(classpath.contains(installation.home.resolve("lib").resolve("a.jar").toString()), classpath);
    assertEquals(List.of("com.intellij.idea.Main", "ijfmt-server"), command.subList(command.size() - 2, command.size()));
  }

  @Test
  void escapesJsonStrings() {
    assertEquals("\"C:\\\\a \\\"b\\\"\\u0001\"", Main.jsonString("C:\\a \"b\"\u0001"));
  }
}
