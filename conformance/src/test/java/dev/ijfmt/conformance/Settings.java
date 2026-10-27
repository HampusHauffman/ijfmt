package dev.ijfmt.conformance;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Conformance configuration from system properties (set by the Gradle {@code conformance} task). */
final class Settings {
  /** Pinned commit of JetBrains/intellij-community used by {@code ./gradlew fetchCorpus}. */
  static final Path DEFAULT_EXTERNAL_CORPUS = Path.of(System.getProperty("user.home"), ".cache", "ijfmt-corpus", "intellij-community");

  final Path ideHome;
  final Path pluginDir;
  final Path work;
  final Path stateRoot;
  final Path resources;
  final String envVarBase;
  final List<Path> corpus;
  final List<String> variantSpecs;
  final List<Mutation> mutations;
  final List<String> extensions;
  final int maxFiles;
  final long seed;

  private Settings(Path ideHome, Path dist, Path work, Path resources) throws IOException {
    this.ideHome = ideHome;
    this.pluginDir = dist.resolve("plugin").resolve("ijfmt-daemon");
    Files.createDirectories(work);
    this.work = work.toRealPath();
    this.stateRoot = Files.createDirectories(this.work.resolveSibling(this.work.getFileName() + "-state"));
    this.resources = resources;
    Matcher env = Pattern.compile("\"envVarBaseName\"\\s*:\\s*\"([^\"]+)\"")
      .matcher(Files.readString(ideHome.resolve("product-info.json"), StandardCharsets.UTF_8));
    this.envVarBase = env.find() ? env.group(1) : "IDEA";

    String corpusSpec = System.getProperty("ijfmt.conformance.corpus");
    List<Path> roots = new ArrayList<>();
    if (corpusSpec != null && !corpusSpec.isBlank()) {
      for (String entry : corpusSpec.split(File.pathSeparator)) roots.add(Path.of(entry.trim()).toAbsolutePath().normalize());
    }
    else {
      roots.add(resources.resolve("fixtures"));
      Path external = Path.of(System.getProperty("ijfmt.conformance.externalCorpus", DEFAULT_EXTERNAL_CORPUS.toString()));
      for (String sub : List.of("java/java-tests/testData/psi/formatter", "java/java-psi-api/src")) {
        if (Files.isDirectory(external.resolve(sub))) roots.add(external.resolve(sub));
      }
    }
    this.corpus = roots;
    // A custom corpus (your repository) brings its own .editorconfig/.idea: by default only add the neutral root marker.
    boolean customCorpus = corpusSpec != null && !corpusSpec.isBlank();
    this.variantSpecs = list("ijfmt.conformance.variants", customCorpus ? "intellij-defaults" : "");
    this.mutations = list("ijfmt.conformance.mutations", "original,flattened,jittered,crlf-trailing").stream()
      .map(m -> Mutation.valueOf(m.toUpperCase(Locale.ROOT).replace('-', '_'))).toList();
    this.extensions = list("ijfmt.conformance.extensions", "java,kt,kts,xml,json,yaml,yml,groovy,gradle");
    this.maxFiles = Integer.parseInt(System.getProperty("ijfmt.conformance.maxFiles", "0"));
    this.seed = Long.parseLong(System.getProperty("ijfmt.conformance.seed", "42"));
  }

  static Settings fromSystemProperties() throws IOException {
    Path ideHome = Path.of(required("ijfmt.ideHome"));
    Path dist = Path.of(required("ijfmt.dist"));
    Path work = Path.of(System.getProperty("ijfmt.work", Path.of(System.getProperty("java.io.tmpdir"), "ijfmt-conformance").toString()));
    Path resources = Path.of(System.getProperty("ijfmt.conformance.resources", "src/test/resources")).toAbsolutePath();
    return new Settings(ideHome, dist, work, resources);
  }

  /** Style variants: name -> directory whose contents (.editorconfig, .idea/codeStyles) go to the root of each tree. */
  Map<String, Path> variants() throws IOException {
    Map<String, Path> result = new LinkedHashMap<>();
    if (variantSpecs.isEmpty()) {
      try (Stream<Path> dirs = Files.list(resources.resolve("variants"))) {
        dirs.filter(Files::isDirectory).sorted().forEach(d -> result.put(d.getFileName().toString(), d));
      }
      return result;
    }
    for (String spec : variantSpecs) {
      Path dir = Files.isDirectory(Path.of(spec)) ? Path.of(spec) : resources.resolve("variants").resolve(spec);
      if (!Files.isDirectory(dir)) throw new IllegalArgumentException("Unknown variant " + spec);
      result.put(dir.getFileName().toString(), dir);
    }
    return result;
  }

  /**
   * Corpus files keyed by {@code <corpus name>/<relative path>}. Nested {@code .editorconfig} files of a corpus are kept
   * (they are part of its style); hidden and build output directories are skipped.
   */
  Map<String, Path> collectCorpus() throws IOException {
    Map<String, Path> files = new TreeMap<>();
    for (Path root : corpus) {
      if (!Files.isDirectory(root)) throw new IllegalArgumentException("Corpus directory not found: " + root);
      String name = root.getFileName().toString();
      if (root.getParent() != null && root.getParent().getFileName() != null) {
        name = root.getParent().getFileName() + "-" + name;
      }
      List<Path> sources;
      try (Stream<Path> paths = Files.walk(root)) {
        sources = paths.filter(Files::isRegularFile)
          .filter(p -> !isExcluded(root.relativize(p)))
          .filter(p -> extensions.contains(ConformanceTest.extension(p.getFileName().toString())))
          .sorted().collect(Collectors.toList());
      }
      if (maxFiles > 0 && sources.size() > maxFiles) {
        double step = (double)sources.size() / maxFiles;
        List<Path> sampled = new ArrayList<>();
        for (int i = 0; i < maxFiles; i++) sampled.add(sources.get((int)(i * step)));
        sources = sampled;
      }
      for (Path p : sources) files.put(name + "/" + root.relativize(p).toString().replace('\\', '/'), p);
    }
    return files;
  }

  /**
   * Style configuration that belongs to the corpus itself (copied unmodified into every tree): nested
   * {@code .editorconfig} files and a root {@code .idea/codeStyles} directory.
   */
  Map<String, Path> corpusEditorConfigs() throws IOException {
    Map<String, Path> files = new TreeMap<>();
    for (Path root : corpus) {
      String name = root.getFileName().toString();
      if (root.getParent() != null && root.getParent().getFileName() != null) name = root.getParent().getFileName() + "-" + name;
      try (Stream<Path> paths = Files.walk(root)) {
        for (Path p : (Iterable<Path>)paths.filter(p -> p.getFileName().toString().equals(".editorconfig"))::iterator) {
          if (!isExcluded(root.relativize(p))) files.put(name + "/" + root.relativize(p).toString().replace('\\', '/'), p);
        }
      }
      for (String scheme : List.of("codeStyleConfig.xml", "Project.xml")) {
        Path file = root.resolve(".idea").resolve("codeStyles").resolve(scheme);
        if (Files.isRegularFile(file)) files.put(name + "/.idea/codeStyles/" + scheme, file);
      }
    }
    return files;
  }

  private static boolean isExcluded(Path relative) {
    for (int i = 0; i < relative.getNameCount() - 1; i++) {
      String part = relative.getName(i).toString();
      if (part.startsWith(".") || part.equals("build") || part.equals("out") || part.equals("target") || part.equals("node_modules")) {
        return true;
      }
    }
    return false;
  }

  private static List<String> list(String property, String defaultValue) {
    String value = System.getProperty(property, defaultValue);
    return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
  }

  private static String required(String property) {
    String value = System.getProperty(property);
    if (value == null) throw new IllegalStateException("System property " + property + " is not set (run via ./gradlew conformance)");
    return value;
  }
}
