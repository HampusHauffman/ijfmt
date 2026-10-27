package dev.ijfmt.cli;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Programmatic access to an ijfmt daemon, for tools and tests that format many files from one JVM.
 *
 * <pre>
 * try (Client client = Client.start(ideHome, pluginDir, stateRoot, Duration.ofMinutes(2))) {
 *   Client.Result result = client.formatText(Path.of("/repo/src/Foo.java"), bytes);
 * }
 * </pre>
 */
public final class Client implements AutoCloseable {
  /** HTTP status and body of a daemon response (200 ok, 415 unsupported, 422 rejected by the formatter). */
  public record Result(int status, byte[] body) {
    public String text() {
      return new String(body, StandardCharsets.UTF_8);
    }

    public boolean ok() {
      return status == 200;
    }
  }

  private final Daemon daemon;
  private final Daemon.Endpoint endpoint;
  private final boolean stopOnClose;

  private Client(Daemon daemon, Daemon.Endpoint endpoint, boolean stopOnClose) {
    this.daemon = daemon;
    this.endpoint = endpoint;
    this.stopOnClose = stopOnClose;
  }

  /**
   * Starts (or reuses) a daemon for the IDE at {@code ideHome} with state kept under {@code stateRoot}. The daemon is
   * stopped when the client is closed.
   *
   * @param configFile ijfmt {@code config.properties} to use (may not exist)
   */
  public static Client start(Path ideHome, Path pluginDir, Path stateRoot, Path configFile, Duration timeout) {
    IdeInstallation ide = IdeInstallation.load(ideHome)
      .orElseThrow(() -> new IllegalArgumentException("Not an IntelliJ installation: " + ideHome));
    Daemon daemon = new Daemon(ide, pluginDir, Config.load(configFile), stateRoot);
    return new Client(daemon, daemon.ensureRunning(true, timeout), true);
  }

  public String ide() {
    return endpoint.ide();
  }

  public Path daemonLog() {
    return daemon.ideLog();
  }

  /** Formats {@code content} (UTF-8) as the editor buffer of {@code path}. */
  public Result formatText(Path path, byte[] content) throws IOException {
    return call("/v1/format?path=" + encode(path), content);
  }

  /** Formats {@code content} (UTF-8) as the editor buffer of {@code path}, restricted to 1-based line ranges "A:B,C:D". */
  public Result formatText(Path path, byte[] content, String lines) throws IOException {
    return call("/v1/format?path=" + encode(path) + "&lines=" + URLEncoder.encode(lines, StandardCharsets.UTF_8), content);
  }

  /** Formats a file in place. Body: {@code {"changed":true|false}}. */
  public Result formatFile(Path path) throws IOException {
    return call("/v1/format-file?path=" + encode(path), null);
  }

  /** Dry run of {@link #formatFile}. */
  public Result checkFile(Path path) throws IOException {
    return call("/v1/check-file?path=" + encode(path), null);
  }

  private Result call(String target, byte[] body) throws IOException {
    Daemon.Response response = daemon.request(endpoint, "POST", target, body, Duration.ofMinutes(5));
    return new Result(response.status(), response.body());
  }

  private static String encode(Path path) {
    return URLEncoder.encode(path.toAbsolutePath().normalize().toString(), StandardCharsets.UTF_8);
  }

  @Override
  public void close() {
    if (stopOnClose) daemon.stop();
  }
}
