package dev.ijfmt.daemon;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal HTTP/1.1 API bound to the loopback interface. Every request must carry the {@code X-Ijfmt-Token} header from
 * the state file.
 * <pre>
 * GET  /v1/status                              -> JSON
 * POST /v1/format?path=P[&amp;lines=A:B,C:D]   body: UTF-8 text  -> formatted text
 * POST /v1/format-file?path=P                 -> JSON {"changed":bool}  (formats in place)
 * POST /v1/check-file?path=P                  -> JSON {"changed":bool}  (dry run)
 * POST /v1/shutdown
 * </pre>
 * Errors: 400 bad request, 401 bad token, 415 unsupported file, 422 rejected by formatter, 500 internal error.
 */
final class HttpApi {
  private static final Logger LOG = Logger.getInstance(HttpApi.class);
  static final String TOKEN_HEADER = "X-Ijfmt-Token";

  private final Formatter formatter;
  private final Path stateFile;
  private final String fingerprint;
  private final long idleTimeoutMinutes;
  private final String ide;
  private final String token = HexFormat.of().formatHex(SecureRandom.getSeed(16));
  private final CountDownLatch shutdown = new CountDownLatch(1);
  private final AtomicLong lastActivity = new AtomicLong(System.nanoTime());
  private final AtomicLong requests = new AtomicLong();
  private final long startedAt = System.currentTimeMillis();
  private HttpServer server;
  private ScheduledExecutorService watchdog;

  HttpApi(Formatter formatter, Path stateFile, String fingerprint, long idleTimeoutMinutes, String ide) {
    this.formatter = formatter;
    this.stateFile = stateFile;
    this.fingerprint = fingerprint;
    this.idleTimeoutMinutes = idleTimeoutMinutes;
    this.ide = ide;
  }

  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16);
    // One worker: requests are executed on the EDT anyway and must not interleave.
    ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
      Thread thread = new Thread(r, "ijfmt-http");
      thread.setDaemon(true);
      return thread;
    });
    server.setExecutor(executor);
    server.createContext("/", this::handle);
    server.start();
    writeStateFile();
    // Queued on the request thread: a request arriving meanwhile simply waits for it.
    executor.execute(() -> {
      try {
        onEdt(() -> {
          formatter.warmUp();
          return null;
        });
      }
      catch (Exception e) {
        LOG.info("ijfmt warm-up failed", e);
      }
    });
    if (idleTimeoutMinutes > 0) {
      watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "ijfmt-idle-watchdog");
        thread.setDaemon(true);
        return thread;
      });
      watchdog.scheduleAtFixedRate(() -> {
        long idleNanos = System.nanoTime() - lastActivity.get();
        if (idleNanos > TimeUnit.MINUTES.toNanos(idleTimeoutMinutes)) {
          LOG.info("ijfmt server idle for " + idleTimeoutMinutes + " minutes, shutting down");
          shutdown.countDown();
        }
      }, 1, 1, TimeUnit.MINUTES);
    }
  }

  String describe() {
    return "127.0.0.1:" + server.getAddress().getPort() + ", state file " + stateFile;
  }

  void awaitShutdown() throws InterruptedException {
    shutdown.await();
    if (watchdog != null) watchdog.shutdownNow();
    server.stop(0);
    try {
      String state = Files.readString(stateFile);
      if (state.contains("\"pid\":" + ProcessHandle.current().pid() + ",")) Files.deleteIfExists(stateFile);
    }
    catch (IOException ignored) {
    }
  }

  private void writeStateFile() throws IOException {
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("pid", ProcessHandle.current().pid());
    state.put("port", server.getAddress().getPort());
    state.put("token", token);
    state.put("fingerprint", fingerprint);
    state.put("ide", ide);
    Files.createDirectories(stateFile.getParent());
    Path tmp = stateFile.resolveSibling(stateFile.getFileName() + "." + ProcessHandle.current().pid() + ".tmp");
    Files.writeString(tmp, toJson(state));
    Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  private void handle(HttpExchange exchange) throws IOException {
    lastActivity.set(System.nanoTime());
    requests.incrementAndGet();
    try {
      if (!tokenMatches(exchange.getRequestHeaders().getFirst(TOKEN_HEADER))) {
        respond(exchange, 401, "text/plain", "Invalid or missing " + TOKEN_HEADER);
        return;
      }
      String route = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
      Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
      switch (route) {
        case "GET /v1/status" -> respond(exchange, 200, "application/json", toJson(status()));
        case "POST /v1/shutdown" -> {
          respond(exchange, 200, "application/json", "{\"ok\":true}");
          shutdown.countDown();
        }
        case "POST /v1/format" -> {
          Path path = requirePath(query);
          String text = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          List<int[]> lines = parseLines(query.get("lines"));
          String result = onEdt(() -> formatter.formatText(path, text, lines));
          respond(exchange, 200, "text/plain; charset=utf-8", result);
        }
        case "POST /v1/format-file", "POST /v1/check-file" -> {
          Path path = requirePath(query);
          Formatter.FileMode mode = route.endsWith("check-file") ? Formatter.FileMode.CHECK : Formatter.FileMode.WRITE;
          boolean changed = onEdt(() -> formatter.formatFile(path, mode));
          respond(exchange, 200, "application/json", "{\"changed\":" + changed + "}");
        }
        default -> respond(exchange, 404, "text/plain", "Unknown endpoint " + route);
      }
    }
    catch (Formatter.UnsupportedFileException e) {
      respond(exchange, 415, "text/plain", e.getMessage());
    }
    catch (Formatter.RejectedException e) {
      respond(exchange, 422, "text/plain", e.getMessage());
    }
    catch (IllegalArgumentException | IOException e) {
      respond(exchange, 400, "text/plain", String.valueOf(e.getMessage()));
    }
    catch (Throwable t) {
      LOG.warn("ijfmt request failed: " + exchange.getRequestURI(), t);
      StringWriter trace = new StringWriter();
      t.printStackTrace(new PrintWriter(trace));
      respond(exchange, 500, "text/plain", trace.toString());
    }
    finally {
      exchange.close();
      lastActivity.set(System.nanoTime());
    }
  }

  private Map<String, Object> status() {
    Map<String, Object> status = new LinkedHashMap<>();
    status.put("pid", ProcessHandle.current().pid());
    status.put("ide", ide);
    status.put("fingerprint", fingerprint);
    status.put("uptimeSeconds", (System.currentTimeMillis() - startedAt) / 1000);
    status.put("requests", requests.get());
    status.put("idleTimeoutMinutes", idleTimeoutMinutes);
    status.put("maxHeapMb", Runtime.getRuntime().maxMemory() / (1024 * 1024));
    return status;
  }

  private boolean tokenMatches(String candidate) {
    return candidate != null && MessageDigest.isEqual(candidate.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
  }

  private interface EdtTask<T> {
    T run() throws Exception;
  }

  private static <T> T onEdt(EdtTask<T> task) throws Exception {
    Object[] result = new Object[1];
    Exception[] failure = new Exception[1];
    ApplicationManager.getApplication().invokeAndWait(() -> {
      try {
        result[0] = task.run();
      }
      catch (Exception e) {
        failure[0] = e;
      }
    }, ModalityState.nonModal());
    if (failure[0] != null) throw failure[0];
    @SuppressWarnings("unchecked") T value = (T)result[0];
    return value;
  }

  private static Path requirePath(Map<String, String> query) {
    String path = query.get("path");
    if (path == null || path.isBlank()) throw new IllegalArgumentException("Missing 'path' query parameter");
    Path result = Path.of(path);
    if (!result.isAbsolute()) throw new IllegalArgumentException("'path' must be absolute: " + path);
    return result.normalize();
  }

  static List<int[]> parseLines(String spec) {
    if (spec == null || spec.isBlank()) return null;
    List<int[]> ranges = new ArrayList<>();
    for (String part : spec.split(",")) {
      String[] bounds = part.trim().split("[:-]");
      try {
        int start = Integer.parseInt(bounds[0].trim());
        int end = bounds.length > 1 ? Integer.parseInt(bounds[1].trim()) : start;
        if (start < 1 || end < start) throw new IllegalArgumentException("Invalid line range: " + part);
        ranges.add(new int[]{start, end});
      }
      catch (NumberFormatException e) {
        throw new IllegalArgumentException("Invalid line range: " + part);
      }
    }
    return ranges;
  }

  private static Map<String, String> parseQuery(String raw) {
    Map<String, String> result = new LinkedHashMap<>();
    if (raw == null) return result;
    for (String pair : raw.split("&")) {
      int eq = pair.indexOf('=');
      String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
      String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
      result.put(key, value);
    }
    return result;
  }

  private static void respond(HttpExchange exchange, int code, String contentType, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", contentType);
    exchange.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
    if (bytes.length > 0) {
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    }
  }

  static String toJson(Map<String, Object> map) {
    StringBuilder json = new StringBuilder("{");
    for (Map.Entry<String, Object> entry : map.entrySet()) {
      if (json.length() > 1) json.append(',');
      json.append(quote(entry.getKey())).append(':');
      Object value = entry.getValue();
      json.append(value instanceof Number || value instanceof Boolean ? value.toString() : quote(String.valueOf(value)));
    }
    return json.append('}').toString();
  }

  private static String quote(String s) {
    StringBuilder out = new StringBuilder("\"");
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) out.append(String.format("\\u%04x", (int)c));
          else out.append(c);
        }
      }
    }
    return out.append('"').toString();
  }
}
