package dev.ijfmt.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class Main {
  static final int EXIT_OK = 0;
  static final int EXIT_CHANGED = 1;
  static final int EXIT_USAGE = 2;
  static final int EXIT_UNSUPPORTED = 3;
  static final int EXIT_FAILED = 4;

  private static final String USAGE = String.join("\n",
    "ijfmt - format code with IntelliJ IDEA's own formatter (a headless IntelliJ daemon does the work)",
    "",
    "Usage:",
    "  ijfmt --stdin-filepath PATH [--lines A:B[,C:D]]   format stdin as if it were PATH, print the result",
    "  ijfmt [--check] [--mask GLOBS] PATH...            format files / directories (recursively) in place",
    "  ijfmt daemon start|stop|restart|status|log        manage the background daemon (start --json: endpoint)",
    "  ijfmt ides                                        list detected IntelliJ installations",
    "",
    "Options:",
    "  --stdin-filepath PATH  Path of the stdin content: picks language, .editorconfig and project code style.",
    "                         The file does not need to exist. Alias: --assume-filename",
    "  --lines A:B[,C:D]      Only reformat these 1-based, inclusive line ranges (stdin mode)",
    "  --check                Don't write anything; print files that need formatting, exit 1 if any",
    "  --mask GLOBS           Comma-separated file name globs for directories, e.g. '*.java,*.kt'",
    "  --ide PATH             IntelliJ installation to use (default: IJFMT_IDE_HOME, config, newest found)",
    "  --no-start             Fail instead of starting the daemon when it isn't running",
    "  --timeout SECONDS      Max wait for daemon startup (default 120)",
    "  -q, --quiet            Only print errors",
    "  -h, --help / --version",
    "",
    "Exit codes: 0 ok, 1 --check found unformatted files, 2 usage/config error, 3 unsupported file, 4 daemon failure",
    "Config: " + Config.configDir().resolve("config.properties") + "  (keys: ide.home, daemon.idleTimeoutMinutes,",
    "        daemon.maxHeap, daemon.startTimeoutSeconds, codestyle.fallback)");

  private final PrintStream out;
  private final PrintStream err;

  Main(PrintStream out, PrintStream err) {
    this.out = out;
    this.err = err;
  }

  public static void main(String[] args) {
    int code;
    PrintStream out = System.out;
    try {
      code = new Main(out, System.err).run(args);
    }
    catch (CliException e) {
      System.err.println("ijfmt: " + e.getMessage());
      code = e.exitCode;
    }
    out.flush();
    System.exit(code);
  }

  static String version() {
    String version = Main.class.getPackage().getImplementationVersion();
    return version != null ? version : "dev";
  }

  /** Parsed command line. */
  static final class Options {
    String stdinPath;
    String lines;
    boolean check;
    boolean quiet;
    boolean noStart;
    boolean json;
    String ide;
    String mask;
    Long timeoutSeconds;
    final List<String> positional = new ArrayList<>();

    static Options parse(String[] args) {
      Options options = new Options();
      for (int i = 0; i < args.length; i++) {
        String arg = args[i];
        String inlineValue = null;
        if (arg.startsWith("--") && arg.contains("=")) {
          inlineValue = arg.substring(arg.indexOf('=') + 1);
          arg = arg.substring(0, arg.indexOf('='));
        }
        switch (arg) {
          case "--stdin-filepath":
          case "--assume-filename":
          case "--stdin":
            options.stdinPath = inlineValue != null ? inlineValue : value(args, ++i, arg);
            break;
          case "--lines":
            options.lines = inlineValue != null ? inlineValue : value(args, ++i, arg);
            break;
          case "--mask":
          case "-m":
            options.mask = inlineValue != null ? inlineValue : value(args, ++i, arg);
            break;
          case "--ide":
            options.ide = inlineValue != null ? inlineValue : value(args, ++i, arg);
            break;
          case "--timeout":
            String timeout = inlineValue != null ? inlineValue : value(args, ++i, arg);
            try {
              options.timeoutSeconds = Long.parseLong(timeout);
            }
            catch (NumberFormatException e) {
              throw new CliException(EXIT_USAGE, "Invalid --timeout: " + timeout);
            }
            break;
          case "--check":
          case "--dry-run":
            options.check = true;
            break;
          case "--no-start":
            options.noStart = true;
            break;
          case "--json":
            options.json = true;
            break;
          case "-q":
          case "--quiet":
            options.quiet = true;
            break;
          case "--":
            for (i++; i < args.length; i++) options.positional.add(args[i]);
            break;
          default:
            if (arg.startsWith("-") && arg.length() > 1) throw new CliException(EXIT_USAGE, "Unknown option " + arg + "\n\n" + USAGE);
            options.positional.add(arg);
        }
      }
      return options;
    }

    private static String value(String[] args, int index, String option) {
      if (index >= args.length) throw new CliException(EXIT_USAGE, "Missing value for " + option);
      return args[index];
    }
  }

  int run(String[] args) {
    for (String arg : args) {
      if (arg.equals("-h") || arg.equals("--help")) {
        out.println(USAGE);
        return EXIT_OK;
      }
      if (arg.equals("--version")) {
        out.println("ijfmt " + version());
        return EXIT_OK;
      }
    }
    Options options = Options.parse(args);
    Config config = Config.load();
    if (!options.positional.isEmpty() && options.positional.get(0).equals("ides") && options.stdinPath == null) {
      List<IdeInstallation> ides = IdeInstallation.detect();
      if (ides.isEmpty()) err.println("No IntelliJ IDEA installation found.");
      for (IdeInstallation ide : ides) out.println(ide);
      return EXIT_OK;
    }
    Duration timeout = Duration.ofSeconds(options.timeoutSeconds != null
                                          ? options.timeoutSeconds
                                          : config.getLong("daemon.startTimeoutSeconds", 120));
    if (!options.positional.isEmpty() && options.positional.get(0).equals("daemon") && options.stdinPath == null) {
      return daemonCommand(options, config, timeout);
    }
    if (options.stdinPath != null) {
      if (!options.positional.isEmpty()) throw new CliException(EXIT_USAGE, "Cannot combine --stdin-filepath with file arguments");
      return formatStdin(options, daemon(options, config), timeout);
    }
    if (options.positional.isEmpty()) throw new CliException(EXIT_USAGE, USAGE);
    if (options.lines != null) throw new CliException(EXIT_USAGE, "--lines is only supported with --stdin-filepath");
    return formatPaths(options, daemon(options, config), timeout);
  }

  private static Daemon daemon(Options options, Config config) {
    return new Daemon(IdeInstallation.resolve(options.ide, config), pluginDir(), config);
  }

  /** {@code IJFMT_PLUGIN_DIR}, else {@code <distribution>/plugin/ijfmt-daemon} next to {@code lib/ijfmt.jar}. */
  static Path pluginDir() {
    String explicit = System.getenv("IJFMT_PLUGIN_DIR");
    if (explicit != null) return Path.of(explicit);
    try {
      Path jar = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      Path dist = jar.getParent().getParent();
      Path plugin = dist.resolve("plugin").resolve("ijfmt-daemon");
      if (Files.isDirectory(plugin)) return plugin;
      throw new CliException(EXIT_USAGE, "Cannot find the daemon plugin at " + plugin + " (set IJFMT_PLUGIN_DIR)");
    }
    catch (URISyntaxException | RuntimeException e) {
      if (e instanceof CliException) throw (CliException)e;
      throw new CliException(EXIT_USAGE, "Cannot locate the ijfmt installation (set IJFMT_PLUGIN_DIR): " + e);
    }
  }

  private int daemonCommand(Options options, Config config, Duration timeout) {
    String action = options.positional.size() > 1 ? options.positional.get(1) : "status";
    Daemon daemon = daemon(options, config);
    switch (action) {
      case "start": {
        Daemon.Endpoint endpoint = daemon.ensureRunning(true, timeout);
        if (options.json) {
          out.println("{\"stateFile\":" + jsonString(daemon.stateFile().toString()) + ",\"pid\":" + endpoint.pid()
                      + ",\"port\":" + endpoint.port() + ",\"token\":" + jsonString(endpoint.token()) + "}");
        }
        else if (!options.quiet) {
          out.println("ijfmt daemon running (pid " + endpoint.pid() + ", " + daemon.ide() + ")");
        }
        return EXIT_OK;
      }
      case "stop":
        if (!options.quiet) out.println(daemon.stop() ? "ijfmt daemon stopped" : "ijfmt daemon was not running");
        return EXIT_OK;
      case "restart": {
        daemon.stop();
        Daemon.Endpoint endpoint = daemon.ensureRunning(true, timeout);
        if (!options.quiet) out.println("ijfmt daemon running (pid " + endpoint.pid() + ", " + daemon.ide() + ")");
        return EXIT_OK;
      }
      case "status": {
        out.println("IDE:        " + daemon.ide());
        out.println("State dir:  " + daemon.stateDir());
        out.println("IDE log:    " + daemon.ideLog());
        var endpoint = daemon.running();
        if (endpoint.isEmpty()) {
          out.println("Daemon:     not running");
          return EXIT_OK;
        }
        try {
          Daemon.Response status = daemon.request(endpoint.get(), "GET", "/v1/status", null, Duration.ofSeconds(5));
          out.println("Daemon:     running on 127.0.0.1:" + endpoint.get().port() + " " + status.text());
        }
        catch (IOException e) {
          out.println("Daemon:     not responding (" + e.getMessage() + ")");
        }
        return EXIT_OK;
      }
      case "log":
        out.println(Daemon.tail(daemon.outputFile(), 20));
        out.println("--- " + daemon.ideLog());
        out.println(Daemon.tail(daemon.ideLog(), 60));
        return EXIT_OK;
      default:
        throw new CliException(EXIT_USAGE, "Unknown daemon command '" + action + "' (start, stop, restart, status, log)");
    }
  }

  private int formatStdin(Options options, Daemon daemon, Duration timeout) {
    byte[] input;
    try {
      input = System.in.readAllBytes();
    }
    catch (IOException e) {
      throw new CliException(EXIT_FAILED, "Cannot read stdin: " + e.getMessage());
    }
    try {
      StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(input));
    }
    catch (CharacterCodingException e) {
      throw new CliException(EXIT_USAGE, "stdin is not valid UTF-8");
    }
    Path path = Path.of(options.stdinPath).toAbsolutePath().normalize();
    String target = "/v1/format?path=" + encode(path.toString())
                    + (options.lines != null ? "&lines=" + encode(options.lines) : "");
    Daemon.Response response = call(daemon, timeout, options.noStart, "POST", target, input);
    switch (response.status()) {
      case 200:
        break;
      case 415:
        throw new CliException(EXIT_UNSUPPORTED, response.text());
      default:
        throw new CliException(EXIT_FAILED, "Formatting " + path + " failed (HTTP " + response.status() + "): " + response.text());
    }
    if (options.check) {
      boolean changed = !java.util.Arrays.equals(input, response.body());
      if (changed && !options.quiet) out.println(path);
      return changed ? EXIT_CHANGED : EXIT_OK;
    }
    out.write(response.body(), 0, response.body().length);
    out.flush();
    return EXIT_OK;
  }

  private int formatPaths(Options options, Daemon daemon, Duration timeout) {
    List<PathMatcher> masks = new ArrayList<>();
    if (options.mask != null) {
      for (String glob : options.mask.split(",")) {
        if (!glob.isBlank()) masks.add(FileSystems.getDefault().getPathMatcher("glob:" + glob.trim()));
      }
    }
    int changed = 0;
    int formatted = 0;
    int failed = 0;
    for (String argument : options.positional) {
      Path root = Path.of(argument).toAbsolutePath().normalize();
      if (!Files.exists(root)) throw new CliException(EXIT_USAGE, "No such file or directory: " + argument);
      boolean explicitFile = !Files.isDirectory(root);
      for (Path file : collectFiles(root, masks)) {
        String target = (options.check ? "/v1/check-file" : "/v1/format-file") + "?path=" + encode(file.toString());
        Daemon.Response response = call(daemon, timeout, options.noStart, "POST", target, null);
        switch (response.status()) {
          case 200:
            formatted++;
            if (response.text().contains("\"changed\":true")) {
              changed++;
              if (options.check) {
                out.println(file);
              }
              else if (!options.quiet) {
                err.println("formatted " + file);
              }
            }
            break;
          case 415:
            if (explicitFile) {
              failed++;
              err.println("ijfmt: skipped " + file + ": " + response.text());
            }
            break;
          default:
            failed++;
            err.println("ijfmt: failed " + file + " (HTTP " + response.status() + "): " + response.text());
        }
      }
    }
    if (!options.quiet) {
      err.println(String.format(Locale.ROOT, "%d file(s) %s, %d %s%s", formatted, options.check ? "checked" : "processed",
                                changed, options.check ? "need formatting" : "changed", failed > 0 ? ", " + failed + " failed" : ""));
    }
    if (failed > 0) return EXIT_FAILED;
    return options.check && changed > 0 ? EXIT_CHANGED : EXIT_OK;
  }

  /** Files below {@code root} (or {@code root} itself), skipping hidden directories such as .git and .idea. */
  static List<Path> collectFiles(Path root, List<PathMatcher> masks) {
    List<Path> files = new ArrayList<>();
    if (!Files.isDirectory(root)) {
      files.add(root);
      return files;
    }
    try {
      Files.walkFileTree(root, new SimpleFileVisitor<>() {
        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
          String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
          return !dir.equals(root) && name.startsWith(".") ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
          if (attrs.isRegularFile() && (masks.isEmpty() || masks.stream().anyMatch(m -> m.matches(file.getFileName())))) {
            files.add(file);
          }
          return FileVisitResult.CONTINUE;
        }
      });
    }
    catch (IOException e) {
      throw new CliException(EXIT_FAILED, "Cannot list " + root + ": " + e.getMessage());
    }
    files.sort(null);
    return files;
  }

  private static Daemon.Response call(Daemon daemon, Duration timeout, boolean noStart, String method, String target, byte[] body) {
    Daemon.Endpoint endpoint = daemon.ensureRunning(!noStart, timeout);
    try {
      return daemon.request(endpoint, method, target, body, Duration.ofMinutes(5));
    }
    catch (IOException first) {
      // The daemon may have just exited (idle timeout, crash): start a fresh one and retry once.
      if (noStart) throw new CliException(EXIT_FAILED, "ijfmt daemon request failed: " + first.getMessage());
      try {
        return daemon.request(daemon.ensureRunning(true, timeout), method, target, body, Duration.ofMinutes(5));
      }
      catch (IOException e) {
        throw new CliException(EXIT_FAILED, "ijfmt daemon request failed: " + e.getMessage());
      }
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  static String jsonString(String value) {
    StringBuilder out = new StringBuilder("\"");
    for (char c : value.toCharArray()) {
      if (c == '"' || c == '\\') out.append('\\').append(c);
      else if (c < 0x20) out.append(String.format(Locale.ROOT, "\\u%04x", (int)c));
      else out.append(c);
    }
    return out.append('"').toString();
  }
}
