package dev.ijfmt.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tiny JSON reader, enough for {@code product-info.json} and the daemon state file. */
final class Json {
  private final String text;
  private int pos;

  private Json(String text) {
    this.text = text;
  }

  static Object parse(String text) {
    Json json = new Json(text);
    json.skipWhitespace();
    Object value = json.readValue();
    json.skipWhitespace();
    if (json.pos != text.length()) throw json.error("Trailing characters");
    return value;
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> parseObject(String text) {
    Object value = parse(text);
    if (!(value instanceof Map)) throw new IllegalArgumentException("Expected a JSON object");
    return (Map<String, Object>)value;
  }

  private Object readValue() {
    if (pos >= text.length()) throw error("Unexpected end of input");
    char c = text.charAt(pos);
    switch (c) {
      case '{': return readObject();
      case '[': return readArray();
      case '"': return readString();
      case 't': expect("true"); return Boolean.TRUE;
      case 'f': expect("false"); return Boolean.FALSE;
      case 'n': expect("null"); return null;
      default:
        if (c == '-' || (c >= '0' && c <= '9')) return readNumber();
        throw error("Unexpected character '" + c + "'");
    }
  }

  private Map<String, Object> readObject() {
    Map<String, Object> result = new LinkedHashMap<>();
    pos++;
    skipWhitespace();
    if (peek() == '}') {
      pos++;
      return result;
    }
    while (true) {
      skipWhitespace();
      if (peek() != '"') throw error("Expected a string key");
      String key = readString();
      skipWhitespace();
      if (peek() != ':') throw error("Expected ':'");
      pos++;
      skipWhitespace();
      result.put(key, readValue());
      skipWhitespace();
      char c = peek();
      pos++;
      if (c == '}') return result;
      if (c != ',') throw error("Expected ',' or '}'");
    }
  }

  private List<Object> readArray() {
    List<Object> result = new ArrayList<>();
    pos++;
    skipWhitespace();
    if (peek() == ']') {
      pos++;
      return result;
    }
    while (true) {
      skipWhitespace();
      result.add(readValue());
      skipWhitespace();
      char c = peek();
      pos++;
      if (c == ']') return result;
      if (c != ',') throw error("Expected ',' or ']'");
    }
  }

  private String readString() {
    StringBuilder out = new StringBuilder();
    pos++;
    while (true) {
      if (pos >= text.length()) throw error("Unterminated string");
      char c = text.charAt(pos++);
      if (c == '"') return out.toString();
      if (c != '\\') {
        out.append(c);
        continue;
      }
      char e = text.charAt(pos++);
      switch (e) {
        case 'n': out.append('\n'); break;
        case 'r': out.append('\r'); break;
        case 't': out.append('\t'); break;
        case 'b': out.append('\b'); break;
        case 'f': out.append('\f'); break;
        case 'u':
          out.append((char)Integer.parseInt(text.substring(pos, pos + 4), 16));
          pos += 4;
          break;
        default: out.append(e);
      }
    }
  }

  private Number readNumber() {
    int start = pos;
    while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) pos++;
    String number = text.substring(start, pos);
    if (number.contains(".") || number.contains("e") || number.contains("E")) return Double.parseDouble(number);
    return Long.parseLong(number);
  }

  private void expect(String literal) {
    if (!text.startsWith(literal, pos)) throw error("Expected " + literal);
    pos += literal.length();
  }

  private char peek() {
    if (pos >= text.length()) throw error("Unexpected end of input");
    return text.charAt(pos);
  }

  private void skipWhitespace() {
    while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
  }

  private IllegalArgumentException error(String message) {
    return new IllegalArgumentException(message + " at offset " + pos);
  }
}
