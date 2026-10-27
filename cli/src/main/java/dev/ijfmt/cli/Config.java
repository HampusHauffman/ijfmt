package dev.ijfmt.cli;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * User configuration ({@code config.properties}) and well-known directories.
 * <pre>
 * ide.home=/path/to/IntelliJ IDEA          # IDE to format with (default: newest installed IntelliJ IDEA)
 * daemon.idleTimeoutMinutes=240            # stop the daemon after this much inactivity (0 = never)
 * daemon.maxHeap=2g                        # -Xmx for the daemon (default: the IDE's vmoptions)
 * daemon.startTimeoutSeconds=120
 * codestyle.fallback=/path/to/scheme.xml   # scheme used when a project has no per-project code style
 * </pre>
 */
final class Config {
  private final Path file;
  private final Properties properties = new Properties();

  private Config(Path file) {
    this.file = file;
    if (Files.isRegularFile(file)) {
      try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
        properties.load(reader);
      }
      catch (IOException e) {
        throw new CliException(2, "Cannot read " + file + ": " + e.getMessage());
      }
    }
  }

  static Config load() {
    String explicit = System.getenv("IJFMT_CONFIG");
    return new Config(explicit != null ? Path.of(explicit) : configDir().resolve("config.properties"));
  }

  static Config load(Path file) {
    return new Config(file);
  }

  Path file() {
    return file;
  }

  String get(String key) {
    String value = properties.getProperty(key);
    return value == null || value.isBlank() ? null : value.trim();
  }

  long getLong(String key, long defaultValue) {
    String value = get(key);
    if (value == null) return defaultValue;
    try {
      return Long.parseLong(value);
    }
    catch (NumberFormatException e) {
      throw new CliException(2, "Invalid number for " + key + " in " + file + ": " + value);
    }
  }

  static Path configDir() {
    String home = System.getProperty("user.home");
    if (isWindows()) return Path.of(envOr("APPDATA", home + "\\AppData\\Roaming"), "ijfmt");
    if (isMac()) return Path.of(home, "Library", "Application Support", "ijfmt");
    return Path.of(envOr("XDG_CONFIG_HOME", home + "/.config"), "ijfmt");
  }

  /** Root of daemon state (private IDE config/system/log dirs, state files). */
  static Path stateRoot() {
    String explicit = System.getenv("IJFMT_STATE_DIR");
    if (explicit != null) return Path.of(explicit);
    String home = System.getProperty("user.home");
    if (isWindows()) return Path.of(envOr("LOCALAPPDATA", home + "\\AppData\\Local"), "ijfmt");
    if (isMac()) return Path.of(home, "Library", "Caches", "ijfmt");
    return Path.of(envOr("XDG_CACHE_HOME", home + "/.cache"), "ijfmt");
  }

  static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
  }

  static boolean isMac() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac");
  }

  private static String envOr(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
  }
}
