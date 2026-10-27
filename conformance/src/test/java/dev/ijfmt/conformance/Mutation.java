package dev.ijfmt.conformance;

import java.util.Locale;
import java.util.Random;

/**
 * Whitespace perturbations applied to corpus files so the formatter has real work to do. The conformance test is
 * differential (ijfmt vs. IntelliJ's own command line formatter on the same input), so mutations don't need to
 * preserve semantics; they only need to resemble code a human might type.
 */
enum Mutation {
  /** The file as-is. */
  ORIGINAL {
    @Override
    String apply(String text, String extension, Random random) {
      return text;
    }
  },
  /** Every line's indentation removed. */
  FLATTENED {
    @Override
    String apply(String text, String extension, Random random) {
      StringBuilder out = new StringBuilder(text.length());
      boolean lineStart = true;
      for (int i = 0; i < text.length(); i++) {
        char c = text.charAt(i);
        if (lineStart && (c == ' ' || c == '\t')) continue;
        lineStart = c == '\n' || c == '\r';
        out.append(c);
      }
      return out.toString();
    }
  },
  /**
   * Random whitespace outside of literals and comments: collapsed / widened gaps, tabs, re-indented, joined and split
   * lines, padding around punctuation and trailing spaces. Only for C-like languages; others get {@link #FLATTENED}.
   */
  JITTERED {
    @Override
    String apply(String text, String extension, Random random) {
      if (!C_LIKE.contains("," + extension.toLowerCase(Locale.ROOT) + ",")) return FLATTENED.apply(text, extension, random);
      return jitter(text, random);
    }
  },
  /** CRLF line separators and trailing whitespace on random lines (exercises line separator + trailing space logic). */
  CRLF_TRAILING {
    @Override
    String apply(String text, String extension, Random random) {
      String[] lines = text.replace("\r\n", "\n").split("\n", -1);
      StringBuilder out = new StringBuilder(text.length() + lines.length * 3);
      for (int i = 0; i < lines.length; i++) {
        out.append(lines[i]);
        if (i < lines.length - 1) {
          if (random.nextInt(4) == 0) out.append(random.nextBoolean() ? "  " : "\t ");
          out.append("\r\n");
        }
      }
      return out.toString();
    }
  };

  private static final String C_LIKE = ",java,kt,kts,groovy,gradle,scala,js,ts,";

  abstract String apply(String text, String extension, Random random);

  String id() {
    return name().toLowerCase(Locale.ROOT).replace('_', '-');
  }

  private static String jitter(String text, Random random) {
    StringBuilder out = new StringBuilder(text.length() + text.length() / 4);
    int i = 0;
    boolean afterLineComment = false;
    while (i < text.length()) {
      char c = text.charAt(i);
      int protectedEnd = protectedTokenEnd(text, i);
      if (protectedEnd > i) {
        out.append(text, i, protectedEnd);
        afterLineComment = text.startsWith("//", i);
        i = protectedEnd;
        continue;
      }
      if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
        int end = i;
        int newlines = 0;
        while (end < text.length() && " \t\r\n".indexOf(text.charAt(end)) >= 0) {
          if (text.charAt(end) == '\n') newlines++;
          end++;
        }
        out.append(randomWhitespace(newlines, afterLineComment, random));
        afterLineComment = false;
        i = end;
        continue;
      }
      afterLineComment = false;
      boolean punctuation = "(){}[],;".indexOf(c) >= 0;
      if (punctuation && random.nextInt(6) == 0 && out.length() > 0 && !Character.isWhitespace(out.charAt(out.length() - 1))) {
        out.append(' ');
      }
      out.append(c);
      if (punctuation && random.nextInt(6) == 0 && i + 1 < text.length() && !Character.isWhitespace(text.charAt(i + 1))) {
        out.append(random.nextInt(3) == 0 ? "\n" : " ");
      }
      i++;
    }
    return out.toString();
  }

  private static String randomWhitespace(int newlines, boolean mustKeepNewline, Random random) {
    StringBuilder ws = new StringBuilder();
    if (newlines == 0) {
      int roll = random.nextInt(10);
      if (roll < 6) ws.append(' ');
      else if (roll < 8) ws.append("   ");
      else if (roll < 9) ws.append('\t');
      else ws.append('\n').append(" ".repeat(random.nextInt(9)));
      return ws.toString();
    }
    if (!mustKeepNewline && newlines == 1 && random.nextInt(7) == 0) return " ";
    if (random.nextInt(5) == 0) ws.append(random.nextBoolean() ? " " : "\t");
    int count = Math.min(newlines + (random.nextInt(8) == 0 ? 1 : 0), 4);
    for (int n = 0; n < count; n++) ws.append('\n');
    int indent = random.nextInt(4);
    ws.append(indent == 0 ? "" : indent == 1 ? "\t" : " ".repeat(random.nextInt(14)));
    return ws.toString();
  }

  /** End offset of a string/char literal, text block or comment starting at {@code i}, or {@code i} if none. */
  private static int protectedTokenEnd(String text, int i) {
    if (text.startsWith("\"\"\"", i)) {
      int end = text.indexOf("\"\"\"", i + 3);
      while (end > 0 && text.charAt(end - 1) == '\\') end = text.indexOf("\"\"\"", end + 1);
      return end < 0 ? text.length() : end + 3;
    }
    if (text.startsWith("//", i)) {
      int end = text.indexOf('\n', i);
      if (end < 0) return text.length();
      return end > i && text.charAt(end - 1) == '\r' ? end - 1 : end;
    }
    if (text.startsWith("/*", i)) {
      int end = text.indexOf("*/", i + 2);
      return end < 0 ? text.length() : end + 2;
    }
    char c = text.charAt(i);
    if (c == '"' || c == '\'') {
      int j = i + 1;
      while (j < text.length()) {
        char d = text.charAt(j);
        if (d == '\\') {
          j += 2;
          continue;
        }
        if (d == c || d == '\n') return j + 1;
        j++;
      }
      return text.length();
    }
    return i;
  }
}
