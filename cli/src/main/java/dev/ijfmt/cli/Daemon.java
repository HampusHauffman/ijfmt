package dev.ijfmt.cli;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/** Manages the headless IntelliJ formatting daemon: locating, starting, stopping and talking to it. */
final class Daemon {
  record Endpoint(long pid, int port, String token, String fingerprint, String ide) {}

  record Response(int status, byte[] body) {
    String text() {
      return new String(body, StandardCharsets.UTF_8);
    }
  }

  private final IdeInstallation ide;
  private final Path pluginDir;
  private final Config config;
  private final Path stateDir;
  private final String fingerprint;

  Daemon(IdeInstallation ide, Path pluginDir, Config config) {
    this(ide, pluginDir, config, Config.stateRoot());
  }

  Daemon(IdeInstallation ide, Path pluginDir, Config config, Path stateRoot) {
    this.ide = ide;
    this.pluginDir = pluginDir;
    this.config = config;
    this.stateDir = stateRoot.resolve(ide.id());
    this.fingerprint = fingerprint(pluginDir, config);
  }

  IdeInstallation ide() {
    return ide;
  }

  Path stateDir() {
    return stateDir;
  }

  Path stateFile() {
    return stateDir.resolve("daemon.json");
  }

  Path outputFile() {
    return stateDir.resolve("daemon.out");
  }

  Path ideLog() {
    return stateDir.resolve("log").resolve("idea.log");
  }

  /**
   * Identifies the daemon build and its startup options, so a client never talks to a daemon started from a different
   * plugin version or configuration (it is restarted instead).
   */
  private static String fingerprint(Path pluginDir, Config config) {
    StringBuilder result = new StringBuilder(Main.version());
    try (Stream<Path> jars = Files.list(pluginDir.resolve("lib"))) {
      for (Path jar : (Iterable<Path>)jars.sorted()::iterator) {
        result.append(';').append(jar.getFileName()).append(':').append(Files.size(jar)).append(':')
          .append(Files.getLastModifiedTime(jar).toMillis());
      }
    }
    catch (IOException e) {
      throw new CliException(2, "Cannot read the ijfmt daemon plugin in " + pluginDir + ": " + e);
    }
    result.append(";fallback=").append(config.get("codestyle.fallback"));
    result.append(";heap=").append(config.get("daemon.maxHeap"));
    result.append(";idle=").append(config.get("daemon.idleTimeoutMinutes"));
    return Integer.toHexString(result.toString().hashCode()) + "-" + Main.version();
  }

  /** Returns a live endpoint, starting the daemon if needed (and allowed). */
  Endpoint ensureRunning(boolean allowStart, Duration timeout) {
    Optional<Endpoint> running = running();
    if (running.isPresent() && running.get().fingerprint().equals(fingerprint)) return running.get();
    if (!allowStart) throw new CliException(4, "The ijfmt daemon is not running (start it with `ijfmt daemon start`)");
    return start(timeout);
  }

  /** The endpoint advertised in the state file, if its process is alive and answering. */
  Optional<Endpoint> running() {
    Endpoint endpoint = readState();
    if (endpoint == null) return Optional.empty();
    if (ProcessHandle.of(endpoint.pid()).map(ProcessHandle::isAlive).orElse(false)) {
      try {
        if (request(endpoint, "GET", "/v1/status", null, Duration.ofSeconds(5)).status() == 200) return Optional.of(endpoint);
      }
      catch (IOException ignored) {
      }
    }
    return Optional.empty();
  }

  private Endpoint readState() {
    try {
      Map<String, Object> state = Json.parseObject(Files.readString(stateFile()));
      return new Endpoint(((Number)state.get("pid")).longValue(), ((Number)state.get("port")).intValue(),
                          (String)state.get("token"), String.valueOf(state.get("fingerprint")), String.valueOf(state.get("ide")));
    }
    catch (IOException | RuntimeException e) {
      return null;
    }
  }

  private Endpoint start(Duration timeout) {
    try {
      Files.createDirectories(stateDir);
      try (FileChannel channel = FileChannel.open(stateDir.resolve("spawn.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
           FileLock ignored = channel.lock()) {
        // Another client may have started it while we were waiting for the lock.
        Optional<Endpoint> running = running();
        if (running.isPresent()) {
          if (running.get().fingerprint().equals(fingerprint)) return running.get();
          stop(running.get());
        }
        Files.deleteIfExists(stateFile());
        Process process = spawn();
        return awaitStartup(process, timeout);
      }
    }
    catch (IOException e) {
      throw new CliException(4, "Cannot start the ijfmt daemon: " + e.getMessage());
    }
  }

  private Process spawn() throws IOException {
    List<String> appArgs = new ArrayList<>(List.of(
      "ijfmt-server",
      "--state-file", stateFile().toString(),
      "--fingerprint", fingerprint,
      "--idle-timeout-minutes", Long.toString(config.getLong("daemon.idleTimeoutMinutes", 240))));
    String fallback = config.get("codestyle.fallback");
    if (fallback != null) {
      appArgs.add("--fallback-settings");
      appArgs.add(Path.of(fallback).toAbsolutePath().toString());
    }
    List<String> command = ide.launchCommand(runtimePluginDir(), stateDir, config.get("daemon.maxHeap"), appArgs);
    command.set(0, detachedJava(command.get(0)));
    command = detachFromSession(command);
    Files.writeString(outputFile(), "# " + String.join(" ", command) + System.lineSeparator());
    ProcessBuilder builder = new ProcessBuilder(command)
      .directory(stateDir.toFile())
      .redirectErrorStream(true)
      .redirectOutput(ProcessBuilder.Redirect.appendTo(outputFile().toFile()))
      .redirectInput(ProcessBuilder.Redirect.from(new File(Config.isWindows() ? "NUL" : "/dev/null")));
    return builder.start();
  }

  /**
   * The daemon runs from a private, fingerprinted copy of the plugin: a running JVM keeps its jars open, which on Windows
   * would otherwise make re-installing ijfmt fail while a daemon is alive.
   */
  private Path runtimePluginDir() throws IOException {
    Path root = stateDir.resolve("plugin-runtime");
    Path target = root.resolve(fingerprint).resolve(pluginDir.getFileName().toString());
    if (!Files.isDirectory(target)) {
      Path staging = Files.createDirectories(root).resolve(fingerprint + ".tmp" + ProcessHandle.current().pid());
      copyTree(pluginDir, staging.resolve(pluginDir.getFileName().toString()));
      Files.move(staging, root.resolve(fingerprint), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }
    try (Stream<Path> old = Files.list(root)) {
      old.filter(p -> !p.getFileName().toString().equals(fingerprint)).forEach(Daemon::deleteQuietly);
    }
    return target;
  }

  private static void copyTree(Path from, Path to) throws IOException {
    try (Stream<Path> paths = Files.walk(from)) {
      for (Path source : (Iterable<Path>)paths::iterator) {
        Path destination = to.resolve(from.relativize(source).toString());
        if (Files.isDirectory(source)) Files.createDirectories(destination);
        else Files.copy(source, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
    }
  }

  private static void deleteQuietly(Path root) {
    try (Stream<Path> paths = Files.walk(root)) {
      for (Path path : (Iterable<Path>)paths.sorted(java.util.Comparator.reverseOrder())::iterator) Files.deleteIfExists(path);
    }
    catch (IOException ignored) {
      // Still in use by a daemon that is shutting down; cleaned up next time.
    }
  }

  /** On Windows use {@code javaw}: no console window, and closing the terminal does not kill the daemon. */
  private static String detachedJava(String java) {
    if (!Config.isWindows()) return java;
    Path javaw = Path.of(java).resolveSibling("javaw.exe");
    return Files.isRegularFile(javaw) ? javaw.toString() : java;
  }

  /** On Linux start the daemon in its own session so terminal signals (Ctrl+C, hangup) don't reach it. */
  private static List<String> detachFromSession(List<String> command) {
    if (Config.isWindows() || Config.isMac()) return command;
    for (String setsid : List.of("/usr/bin/setsid", "/bin/setsid")) {
      if (Files.isExecutable(Path.of(setsid))) {
        List<String> result = new ArrayList<>(command.size() + 1);
        result.add(setsid);
        result.addAll(command);
        return result;
      }
    }
    return command;
  }

  private Endpoint awaitStartup(Process process, Duration timeout) throws IOException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      Endpoint endpoint = readState();
      if (endpoint != null && endpoint.fingerprint().equals(fingerprint)) {
        try {
          if (request(endpoint, "GET", "/v1/status", null, Duration.ofSeconds(5)).status() == 200) return endpoint;
        }
        catch (IOException ignored) {
        }
      }
      if (!process.isAlive() && readState() == null) {
        throw new CliException(4, "The ijfmt daemon exited during startup (code " + process.exitValue() + ").\n" + tail(outputFile(), 30)
                                  + "\nSee " + ideLog());
      }
      try {
        Thread.sleep(100);
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new CliException(4, "Interrupted while waiting for the ijfmt daemon");
      }
    }
    throw new CliException(4, "The ijfmt daemon did not start within " + timeout.toSeconds() + "s. See " + outputFile() + " and " + ideLog());
  }

  /** Stops the daemon if it is running. Returns whether one was running. */
  boolean stop() {
    Endpoint endpoint = readState();
    if (endpoint == null) return false;
    boolean alive = ProcessHandle.of(endpoint.pid()).map(ProcessHandle::isAlive).orElse(false);
    if (alive) stop(endpoint);
    try {
      Files.deleteIfExists(stateFile());
    }
    catch (IOException ignored) {
    }
    return alive;
  }

  private void stop(Endpoint endpoint) {
    try {
      request(endpoint, "POST", "/v1/shutdown", null, Duration.ofSeconds(10));
    }
    catch (IOException ignored) {
    }
    Optional<ProcessHandle> process = ProcessHandle.of(endpoint.pid());
    if (process.isEmpty()) return;
    try {
      process.get().onExit().get(30, java.util.concurrent.TimeUnit.SECONDS);
    }
    catch (Exception e) {
      process.get().destroyForcibly();
    }
  }

  /** A minimal HTTP/1.1 exchange over a loopback socket (fast to start, no extra JDK modules). */
  Response request(Endpoint endpoint, String method, String target, byte[] body, Duration timeout) throws IOException {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), endpoint.port()), 2000);
      socket.setSoTimeout((int)Math.min(Integer.MAX_VALUE, timeout.toMillis()));
      OutputStream out = new BufferedOutputStream(socket.getOutputStream());
      byte[] payload = body == null ? new byte[0] : body;
      String head = method + " " + target + " HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:" + endpoint.port() + "\r\n" +
                    "Connection: close\r\n" +
                    "X-Ijfmt-Token: " + endpoint.token() + "\r\n" +
                    "Content-Type: text/plain; charset=utf-8\r\n" +
                    "Content-Length: " + payload.length + "\r\n\r\n";
      out.write(head.getBytes(StandardCharsets.US_ASCII));
      out.write(payload);
      out.flush();
      return readResponse(socket.getInputStream());
    }
    catch (SocketTimeoutException e) {
      throw new IOException("Timed out waiting for the ijfmt daemon after " + timeout.toSeconds() + "s", e);
    }
  }

  private static Response readResponse(InputStream in) throws IOException {
    ByteArrayOutputStream headBytes = new ByteArrayOutputStream();
    int matched = 0;
    while (matched < 4) {
      int b = in.read();
      if (b < 0) throw new IOException("Connection closed by the ijfmt daemon");
      headBytes.write(b);
      matched = (b == "\r\n\r\n".charAt(matched)) ? matched + 1 : (b == '\r' ? 1 : 0);
    }
    String[] lines = headBytes.toString(StandardCharsets.US_ASCII).split("\r\n");
    String[] statusLine = lines[0].split(" ", 3);
    if (statusLine.length < 2) throw new IOException("Malformed response from the ijfmt daemon: " + lines[0]);
    int status = Integer.parseInt(statusLine[1]);
    long length = -1;
    for (int i = 1; i < lines.length; i++) {
      int colon = lines[i].indexOf(':');
      if (colon > 0 && lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT).equals("content-length")) {
        length = Long.parseLong(lines[i].substring(colon + 1).trim());
      }
    }
    byte[] body = length >= 0 ? in.readNBytes((int)length) : in.readAllBytes();
    return new Response(status, body);
  }

  static String tail(Path file, int lines) {
    try {
      List<String> all = Files.readAllLines(file);
      return String.join(System.lineSeparator(), all.subList(Math.max(0, all.size() - lines), all.size()));
    }
    catch (IOException e) {
      return "(no output: " + e.getMessage() + ")";
    }
  }
}
