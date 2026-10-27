package dev.ijfmt.daemon;

import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ApplicationStarter;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.application.ex.ApplicationEx;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;

/**
 * {@code idea ijfmt-server --state-file <path> [--idle-timeout-minutes <n>] [--fingerprint <id>] [--fallback-settings <xml>]}
 * <p>
 * Starts a headless IntelliJ that serves formatting requests over HTTP on 127.0.0.1 and advertises its port and access
 * token in the state file. Launched and managed by the {@code ijfmt} client.
 */
public final class IjfmtServerStarter implements ApplicationStarter {
  private static final Logger LOG = Logger.getInstance(IjfmtServerStarter.class);

  @Override
  public boolean isHeadless() {
    return true;
  }

  @Override
  public int getRequiredModality() {
    return NOT_IN_EDT;
  }

  @Override
  public void main(@NotNull List<String> args) {
    int exitCode = 0;
    try {
      Options options = Options.parse(args.subList(1, args.size()));
      SaveGuard.activate();
      Formatter formatter = Formatter.create(options.fallbackSettings);
      try {
        HttpApi api = new HttpApi(formatter, options.stateFile, options.fingerprint, options.idleTimeoutMinutes, describeIde());
        api.start();
        LOG.info("ijfmt server ready: " + api.describe());
        api.awaitShutdown();
      }
      finally {
        formatter.dispose();
      }
    }
    catch (IllegalArgumentException e) {
      System.err.println("ijfmt-server: " + e.getMessage());
      exitCode = 2;
    }
    catch (Throwable t) {
      LOG.error("ijfmt server failed", t);
      exitCode = 1;
    }
    ((ApplicationEx)ApplicationManager.getApplication()).exit(true, true, exitCode);
  }

  private static String describeIde() {
    ApplicationInfo info = ApplicationInfo.getInstance();
    return info.getBuild().asString() + " (" + info.getFullApplicationName() + ", " + PathManager.getHomePath() + ")";
  }

  private record Options(Path stateFile, String fingerprint, long idleTimeoutMinutes, @Nullable Path fallbackSettings) {
    static Options parse(List<String> args) {
      Path stateFile = null;
      String fingerprint = "";
      long idle = 0;
      Path fallback = null;
      for (int i = 0; i < args.size(); i++) {
        String arg = args.get(i);
        String value = i + 1 < args.size() ? args.get(i + 1) : null;
        switch (arg) {
          case "--state-file" -> stateFile = Path.of(require(arg, value));
          case "--fingerprint" -> fingerprint = require(arg, value);
          case "--idle-timeout-minutes" -> idle = Long.parseLong(require(arg, value));
          case "--fallback-settings" -> fallback = Path.of(require(arg, value));
          default -> throw new IllegalArgumentException("Unknown option " + arg);
        }
        i++;
      }
      if (stateFile == null) throw new IllegalArgumentException("--state-file is required");
      return new Options(stateFile, fingerprint, idle, fallback);
    }

    private static String require(String option, @Nullable String value) {
      if (value == null) throw new IllegalArgumentException("Missing value for " + option);
      return value;
    }
  }
}
