package dev.ijfmt.cli;

/** An error reported to the user as a single message with a specific exit code. */
final class CliException extends RuntimeException {
  final int exitCode;

  CliException(int exitCode, String message) {
    super(message);
    this.exitCode = exitCode;
  }
}
