package dev.ijfmt.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/** An IntelliJ IDEA installation, described by its {@code product-info.json}. */
final class IdeInstallation {
  final Path home;
  final String name;
  final String version;
  final String buildNumber;
  final String productCode;
  private final Map<String, Object> productInfo;

  private IdeInstallation(Path home, Map<String, Object> productInfo) {
    this.home = home;
    this.productInfo = productInfo;
    this.name = String.valueOf(productInfo.getOrDefault("name", "IntelliJ"));
    this.version = String.valueOf(productInfo.getOrDefault("version", "?"));
    this.buildNumber = String.valueOf(productInfo.getOrDefault("buildNumber", "0"));
    this.productCode = String.valueOf(productInfo.getOrDefault("productCode", "IDE"));
  }

  String id() {
    return productCode + "-" + buildNumber;
  }

  @Override
  public String toString() {
    return name + " " + version + " (" + id() + ") at " + home;
  }

  static Optional<IdeInstallation> load(Path home) {
    Path normalized = home.toAbsolutePath().normalize();
    for (Path candidate : List.of(normalized, normalized.resolve("Contents"))) {
      Path info = candidate.resolve("product-info.json");
      if (Files.isRegularFile(info) && Files.isDirectory(candidate.resolve("lib"))) {
        try {
          return Optional.of(new IdeInstallation(candidate, Json.parseObject(Files.readString(info, StandardCharsets.UTF_8))));
        }
        catch (IOException | IllegalArgumentException e) {
          return Optional.empty();
        }
      }
    }
    return Optional.empty();
  }

  /**
   * Resolves the IDE: explicit path, then {@code IJFMT_IDE_HOME}, then {@code ide.home} from the config file, then the
   * newest IntelliJ IDEA found in the usual install locations (including JetBrains Toolbox).
   */
  static IdeInstallation resolve(String explicit, Config config) {
    String configured = explicit != null ? explicit : System.getenv("IJFMT_IDE_HOME");
    if (configured == null) configured = config.get("ide.home");
    if (configured != null && !configured.isBlank()) {
      String path = configured;
      return load(Path.of(path)).orElseThrow(
        () -> new CliException(2, "Not an IntelliJ installation (no product-info.json): " + path));
    }
    return detect().stream().findFirst().orElseThrow(() -> new CliException(2,
      "Cannot find an IntelliJ IDEA installation. Use --ide <path>, set IJFMT_IDE_HOME, or set ide.home in " + config.file()));
  }

  /** All IntelliJ IDEA installations found in well-known locations, newest build first. */
  static List<IdeInstallation> detect() {
    String userHome = System.getProperty("user.home");
    List<Path> roots = new ArrayList<>();
    String localAppData = System.getenv("LOCALAPPDATA");
    String programFiles = System.getenv("ProgramFiles");
    if (localAppData != null) {
      roots.add(Path.of(localAppData, "Programs"));
      roots.add(Path.of(localAppData, "JetBrains", "Toolbox", "apps"));
    }
    if (programFiles != null) roots.add(Path.of(programFiles, "JetBrains"));
    roots.add(Path.of("/Applications"));
    roots.add(Path.of(userHome, "Applications"));
    roots.add(Path.of(userHome, "Library", "Application Support", "JetBrains", "Toolbox", "apps"));
    roots.add(Path.of(userHome, ".local", "share", "JetBrains", "Toolbox", "apps"));
    roots.add(Path.of("/opt"));
    roots.add(Path.of("/usr/local"));
    roots.add(Path.of("/snap"));

    List<IdeInstallation> found = new ArrayList<>();
    for (Path root : roots) {
      if (!Files.isDirectory(root)) continue;
      // Install dirs are at most a few levels below the roots (Toolbox: apps/<product>/<channel>/<build>).
      try (Stream<Path> paths = Files.find(root, 4, (p, a) -> a.isDirectory() && looksLikeIdea(p))) {
        paths.forEach(p -> load(p).filter(IdeInstallation::isIntellijIdea).ifPresent(found::add));
      }
      catch (IOException | RuntimeException ignored) {
      }
    }
    found.sort(Comparator.comparing((IdeInstallation i) -> paddedBuild(i.buildNumber)).reversed());
    List<IdeInstallation> unique = new ArrayList<>();
    for (IdeInstallation ide : found) {
      if (unique.stream().noneMatch(u -> u.home.equals(ide.home))) unique.add(ide);
    }
    return unique;
  }

  private static boolean looksLikeIdea(Path dir) {
    String name = dir.getFileName() == null ? "" : dir.getFileName().toString().toLowerCase(Locale.ROOT);
    return name.contains("idea") || name.contains("intellij") || name.matches("\\d+(\\.\\d+)+") || name.equals("contents");
  }

  private boolean isIntellijIdea() {
    return productCode.equals("IU") || productCode.equals("IC") || productCode.equals("IE") || name.contains("IntelliJ");
  }

  private static String paddedBuild(String build) {
    StringBuilder result = new StringBuilder();
    for (String part : build.split("\\.")) result.append(String.format("%10s", part)).append('.');
    return result.toString();
  }

  /** The {@code launch} entry of {@code product-info.json} for this OS/architecture. */
  @SuppressWarnings("unchecked")
  private Map<String, Object> launchEntry() {
    String os = osName();
    String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
    String normalizedArch = arch.equals("x86_64") ? "amd64" : arch.equals("arm64") ? "aarch64" : arch;
    List<Object> entries = (List<Object>)productInfo.getOrDefault("launch", List.of());
    Map<String, Object> fallback = null;
    for (Object entry : entries) {
      Map<String, Object> launch = (Map<String, Object>)entry;
      if (!os.equals(launch.get("os"))) continue;
      if (normalizedArch.equals(launch.get("arch"))) return launch;
      if (fallback == null) fallback = launch;
    }
    if (fallback == null) throw new CliException(2, "product-info.json of " + home + " has no launch entry for " + os);
    return fallback;
  }

  private static String osName() {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    if (os.startsWith("windows")) return "Windows";
    if (os.startsWith("mac")) return "macOS";
    return "Linux";
  }

  Path javaExecutable() {
    Object path = launchEntry().get("javaExecutablePath");
    if (path == null) throw new CliException(2, "product-info.json of " + home + " does not name a bundled JVM");
    return home.resolve(path.toString()).normalize();
  }

  /**
   * The JVM command line IntelliJ's native launcher would use (classpath, VM options, JVM arguments), with private
   * config/system/log directories so the daemon never interferes with a running IDE, plus our plugin.
   */
  @SuppressWarnings("unchecked")
  List<String> launchCommand(Path pluginDir, Path stateDir, String maxHeap, List<String> appArgs) throws IOException {
    Map<String, Object> launch = launchEntry();
    List<String> command = new ArrayList<>();
    command.add(javaExecutable().toString());

    Object vmOptionsFile = launch.get("vmOptionsFilePath");
    if (vmOptionsFile != null) {
      Path vmOptions = home.resolve(vmOptionsFile.toString());
      if (Files.isRegularFile(vmOptions)) {
        for (String line : Files.readAllLines(vmOptions)) {
          String option = line.trim();
          if (option.isEmpty() || option.startsWith("#")) continue;
          if (maxHeap != null && option.startsWith("-Xmx")) continue;
          command.add(option);
        }
      }
    }
    for (Object argument : (List<Object>)launch.getOrDefault("additionalJvmArguments", List.of())) {
      String value = expand(argument.toString());
      if (value.equals("-Dsplash=true")) continue;
      command.add(value);
    }
    if (maxHeap != null) command.add("-Xmx" + maxHeap);
    command.add("-Djava.awt.headless=true");
    command.add("-Didea.config.path=" + stateDir.resolve("config"));
    command.add("-Didea.system.path=" + stateDir.resolve("system"));
    command.add("-Didea.log.path=" + stateDir.resolve("log"));
    command.add("-Didea.plugins.path=" + stateDir.resolve("plugins"));
    command.add("-Dplugin.path=" + pluginDir);
    command.add("-Djb.consents.confirmation.enabled=false");
    command.add("-Didea.initially.ask.config=never");

    List<String> classpath = new ArrayList<>();
    for (Object jar : (List<Object>)launch.getOrDefault("bootClassPathJarNames", List.of())) {
      classpath.add(home.resolve("lib").resolve(jar.toString()).toString());
    }
    command.add("-cp");
    command.add(String.join(java.io.File.pathSeparator, classpath));
    command.add(Objects.toString(launch.get("mainClass"), "com.intellij.idea.Main"));
    command.addAll(appArgs);
    return command;
  }

  private String expand(String value) {
    String appPackage = home.getParent() != null ? home.getParent().toString() : home.toString();
    return value.replace("%IDE_HOME%", home.toString())
      .replace("$IDE_HOME", home.toString())
      .replace("$APP_PACKAGE", appPackage);
  }
}
