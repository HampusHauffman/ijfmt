package dev.ijfmt.conformance;

import dev.ijfmt.cli.Client;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Differential conformance test: for every (style variant x input mutation x corpus file), the output of ijfmt must be
 * byte-for-byte identical to IntelliJ's stock command line formatter ({@code bin/format.sh|bat}) of the same IDE build.
 * <p>
 * Both run on identical directory trees. ijfmt is checked in all three modes:
 * <ul>
 *   <li>buffer mode (stdin / editor integration), done before anything touches the disk;</li>
 *   <li>check mode (does the file need formatting?);</li>
 *   <li>file mode (format in place).</li>
 * </ul>
 * Configure with Gradle properties, e.g. {@code ./gradlew conformance -Pconformance.corpus=/path/to/your/repo}:
 * {@code corpus} (path list), {@code variants} (names in resources/variants or directories), {@code mutations},
 * {@code extensions}, {@code maxFiles} (per corpus root), {@code seed}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConformanceTest {
  private static final Pattern ORACLE_LINE = Pattern.compile("^Formatting (.+?)\\.\\.\\.(.+)$");

  private Settings settings;
  private Client client;

  @BeforeAll
  void startDaemon() throws IOException {
    settings = Settings.fromSystemProperties();
    client = Client.start(settings.ideHome, settings.pluginDir, settings.stateRoot.resolve("daemon"),
                          settings.stateRoot.resolve("no-config.properties"), Duration.ofMinutes(3));
    System.out.println("[conformance] ijfmt daemon: " + client.ide());
  }

  @AfterAll
  void stopDaemon() {
    if (client != null) client.close();
  }

  Client client() {
    return client;
  }

  Settings settings() {
    return settings;
  }

  /** One input file of one tree: {@code <variant>/<mutation>/<corpus>/<relative path>}. */
  private record Case(String variant, Mutation mutation, String relative) {
    Path in(Path root) {
      return root.resolve(variant).resolve(mutation.id()).resolve(relative);
    }
  }

  private record Outcome(int bufferStatus, byte[] buffer, int checkStatus, Boolean checkChanged, int fileStatus, byte[] file) {}

  @TestFactory
  Stream<DynamicNode> ijfmtMatchesIntellijCommandLineFormatter() throws Exception {
    Path work = settings.work;
    deleteRecursively(work);
    Path inputs = Files.createDirectories(work.resolve("input"));
    Path oracleRoot = Files.createDirectories(work.resolve("oracle"));
    Path ijfmtRoot = Files.createDirectories(work.resolve("ijfmt"));

    // 1. Build identical trees: <root>/<variant>/<mutation>/ holds the variant's .editorconfig/.idea and the corpus.
    Map<String, Path> corpusFiles = settings.collectCorpus();
    Map<String, Path> nestedEditorConfigs = settings.corpusEditorConfigs();
    List<Case> cases = new ArrayList<>();
    for (Map.Entry<String, Path> variant : settings.variants().entrySet()) {
      for (Mutation mutation : settings.mutations) {
        for (Path root : List.of(inputs, oracleRoot, ijfmtRoot)) {
          Path treeRoot = root.resolve(variant.getKey()).resolve(mutation.id());
          copyTree(variant.getValue(), treeRoot);
          for (Map.Entry<String, Path> editorConfig : nestedEditorConfigs.entrySet()) {
            Path target = treeRoot.resolve(editorConfig.getKey());
            Files.createDirectories(target.getParent());
            Files.copy(editorConfig.getValue(), target);
          }
        }
        for (Map.Entry<String, Path> file : corpusFiles.entrySet()) {
          Case c = new Case(variant.getKey(), mutation, file.getKey());
          byte[] original = Files.readAllBytes(file.getValue());
          String text = new String(original, StandardCharsets.UTF_8);
          long seed = settings.seed * 31 + (c.variant + "/" + c.mutation + "/" + c.relative).hashCode();
          byte[] mutated = isUtf8(original)
                           ? mutation.apply(text, extension(file.getKey()), new Random(seed)).getBytes(StandardCharsets.UTF_8)
                           : original;
          for (Path root : List.of(inputs, oracleRoot, ijfmtRoot)) {
            Path target = c.in(root);
            Files.createDirectories(target.getParent());
            Files.write(target, mutated);
          }
          cases.add(c);
        }
      }
    }
    System.out.printf("[conformance] %d cases: %d files x %d variants %s x %d mutations %s%n", cases.size(), corpusFiles.size(),
                      settings.variants().size(), settings.variants().keySet(), settings.mutations.size(), settings.mutations);

    // 2. ijfmt: buffer + check mode first (disk untouched), then in-place formatting.
    long ijStart = System.nanoTime();
    Map<Case, Outcome> outcomes = new LinkedHashMap<>();
    for (Case c : cases) {
      Path file = c.in(ijfmtRoot);
      byte[] input = Files.readAllBytes(file);
      Client.Result buffer = isUtf8(input) ? client.formatText(file, input) : null;
      Client.Result check = client.checkFile(file);
      Client.Result format = client.formatFile(file);
      outcomes.put(c, new Outcome(buffer == null ? -1 : buffer.status(), buffer == null ? null : buffer.body(),
                                  check.status(), check.ok() ? check.text().contains("\"changed\":true") : null,
                                  format.status(), Files.readAllBytes(file)));
    }
    double ijSeconds = (System.nanoTime() - ijStart) / 1e9;

    // 3. The oracle: IntelliJ's own command line formatter, one recursive run over the whole tree.
    long oracleStart = System.nanoTime();
    Map<String, String> oracleStatus = runOracle(oracleRoot);
    double oracleSeconds = (System.nanoTime() - oracleStart) / 1e9;

    // 4. Compare.
    int changedByOracle = 0;
    Map<String, List<DynamicNode>> tests = new TreeMap<>();
    StringBuilder report = new StringBuilder();
    int failures = 0;
    for (Case c : cases) {
      byte[] input = Files.readAllBytes(c.in(inputs));
      byte[] expected = Files.readAllBytes(c.in(oracleRoot));
      String status = oracleStatus.get(normalizeKey(oracleRoot.relativize(c.in(oracleRoot)).toString()));
      if (!Arrays.equals(input, expected)) changedByOracle++;
      String problem = compare(c, status, input, expected, outcomes.get(c));
      if (problem != null) {
        failures++;
        report.append("=== ").append(c.variant).append('/').append(c.mutation.id()).append('/').append(c.relative).append('\n')
          .append(problem).append("\n  input:  ").append(c.in(inputs)).append("\n  oracle: ").append(c.in(oracleRoot))
          .append("\n  ijfmt:  ").append(c.in(ijfmtRoot)).append("\n\n");
      }
      String group = c.variant + " / " + c.mutation.id();
      tests.computeIfAbsent(group, k -> new ArrayList<>()).add(DynamicTest.dynamicTest(c.relative, () -> {
        if (problem != null) fail(problem);
      }));
    }
    Files.writeString(work.resolve("report.txt"), report.length() == 0 ? "All cases identical.\n" : report);
    System.out.printf("[conformance] oracle (idea format): %.1fs, ijfmt (3 modes): %.1fs%n", oracleSeconds, ijSeconds);
    System.out.printf("[conformance] %d/%d cases identical; the oracle changed %d inputs (%.0f%%); report: %s%n",
                      cases.size() - failures, cases.size(), changedByOracle, 100.0 * changedByOracle / Math.max(1, cases.size()),
                      work.resolve("report.txt"));
    assertEquals(cases.size(), outcomes.size());
    return tests.entrySet().stream().map(e -> DynamicContainer.dynamicContainer(e.getKey(), e.getValue().stream()));
  }

  /** @return null if ijfmt behaved exactly like the oracle, else a description of the difference */
  private static String compare(Case c, String oracleStatus, byte[] input, byte[] expected, Outcome actual) {
    if (oracleStatus == null) return "The oracle did not report this file (not formatted at all?)";
    switch (oracleStatus) {
      case "OK": {
        List<String> problems = new ArrayList<>();
        if (actual.fileStatus != 200) problems.add("file mode returned HTTP " + actual.fileStatus);
        else if (!Arrays.equals(expected, actual.file)) problems.add("file mode differs:\n" + diff(expected, actual.file));
        if (actual.bufferStatus != -1) {
          if (actual.bufferStatus != 200) problems.add("buffer mode returned HTTP " + actual.bufferStatus);
          else if (!Arrays.equals(expected, actual.buffer)) problems.add("buffer mode differs:\n" + diff(expected, actual.buffer));
        }
        boolean oracleChanged = !Arrays.equals(input, expected);
        if (actual.checkStatus != 200) problems.add("check mode returned HTTP " + actual.checkStatus);
        else if (actual.checkChanged != oracleChanged) {
          problems.add("check mode says changed=" + actual.checkChanged + " but the oracle " + (oracleChanged ? "changed" : "kept") + " the file");
        }
        return problems.isEmpty() ? null : String.join("\n", problems);
      }
      case "Skipped, not supported.":
      case "Skipped, binary file.":
        return actual.fileStatus == 415 && (actual.bufferStatus == -1 || actual.bufferStatus == 415)
               ? null : "oracle: '" + oracleStatus + "', ijfmt file/buffer status " + actual.fileStatus + "/" + actual.bufferStatus;
      case "Skipped, rejected by formatter.":
        return actual.fileStatus == 422 ? null : "oracle: '" + oracleStatus + "', ijfmt file status " + actual.fileStatus;
      default:
        return "oracle reported '" + oracleStatus + "'" + (Arrays.equals(expected, actual.file) ? "" : "\n" + diff(expected, actual.file));
    }
  }

  /** Runs {@code bin/format.(sh|bat) -r -allowDefaults -m <masks> <root>} with a private IDE config. */
  private Map<String, String> runOracle(Path root) throws IOException, InterruptedException {
    Path state = Files.createDirectories(settings.stateRoot.resolve("oracle"));
    Path properties = state.resolve("idea.properties");
    Files.writeString(properties, String.join("\n",
      "idea.config.path=" + slashes(state.resolve("config")),
      "idea.system.path=" + slashes(state.resolve("system")),
      "idea.log.path=" + slashes(state.resolve("log")),
      "idea.plugins.path=" + slashes(state.resolve("plugins")), ""));
    String masks = settings.extensions.stream().map(e -> "*." + e).collect(Collectors.joining(","));
    List<String> command = new ArrayList<>();
    boolean windows = File.separatorChar == '\\';
    if (windows) {
      // cmd.exe treats commas as separators and mangles quoted paths unless the whole command line is quoted (/s /c "...").
      String line = quote(settings.ideHome.resolve("bin").resolve("format.bat").toString())
                    + " -r -allowDefaults -m " + quote(masks) + " " + quote(root.toString());
      command.addAll(List.of("cmd.exe", "/s", "/c", "\"" + line + "\""));
    }
    else {
      command.addAll(List.of(settings.ideHome.resolve("bin").resolve("format.sh").toString(),
                             "-r", "-allowDefaults", "-m", masks, root.toString()));
    }
    Path log = settings.work.resolve("oracle.log");
    ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
    builder.environment().put(settings.envVarBase + "_PROPERTIES", properties.toString());
    Process process = builder.start();
    int exit = process.waitFor();
    List<String> lines = new String(Files.readAllBytes(log), java.nio.charset.Charset.defaultCharset()).lines().toList();
    if (exit != 0) throw new IllegalStateException("The IntelliJ command line formatter failed (exit " + exit + "), see " + log);
    Map<String, String> result = new LinkedHashMap<>();
    Path realRoot = root.toRealPath();
    for (String line : lines) {
      Matcher m = ORACLE_LINE.matcher(line.trim());
      if (!m.matches()) continue;
      Path path = Path.of(m.group(1)).toRealPath();
      result.put(normalizeKey(realRoot.relativize(path).toString()), m.group(2).trim());
    }
    return result;
  }

  private static String quote(String value) {
    return "\"" + value + "\"";
  }

  private static String normalizeKey(String relative) {
    return relative.replace('\\', '/');
  }

  private static String slashes(Path path) {
    return path.toString().replace('\\', '/');
  }

  static String diff(byte[] expected, byte[] actual) {
    String[] e = new String(expected, StandardCharsets.UTF_8).split("\n", -1);
    String[] a = new String(actual, StandardCharsets.UTF_8).split("\n", -1);
    int first = 0;
    while (first < e.length && first < a.length && e[first].equals(a[first])) first++;
    StringBuilder out = new StringBuilder("  first difference at line " + (first + 1) + " (expected " + e.length + " lines, got " + a.length + ")\n");
    for (int i = Math.max(0, first - 2); i < Math.min(e.length, first + 3); i++) out.append("  - ").append(visible(e[i])).append('\n');
    for (int i = Math.max(0, first - 2); i < Math.min(a.length, first + 3); i++) out.append("  + ").append(visible(a[i])).append('\n');
    return out.toString();
  }

  private static String visible(String line) {
    return line.replace("\r", "\\r").replace("\t", "\\t") + "$";
  }

  static boolean isUtf8(byte[] bytes) {
    try {
      StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
      return true;
    }
    catch (CharacterCodingException e) {
      return false;
    }
  }

  static String extension(String name) {
    int dot = name.lastIndexOf('.');
    return dot < 0 ? "" : name.substring(dot + 1);
  }

  static void copyTree(Path from, Path to) throws IOException {
    try (Stream<Path> paths = Files.walk(from)) {
      for (Path source : (Iterable<Path>)paths::iterator) {
        Path target = to.resolve(from.relativize(source).toString());
        if (Files.isDirectory(source)) Files.createDirectories(target);
        else Files.copy(source, target);
      }
    }
  }

  static void deleteRecursively(Path root) throws IOException {
    if (!Files.exists(root)) return;
    try (Stream<Path> paths = Files.walk(root)) {
      for (Path path : (Iterable<Path>)paths.sorted((x, y) -> y.getNameCount() - x.getNameCount())::iterator) {
        Files.deleteIfExists(path);
      }
    }
  }
}
