package com.grahambartley.engine;

import java.util.HashMap;
import java.util.Map;

/**
 * Minimal JSON helpers for the {@code --stdio} protocol.
 *
 * <p>The engine deliberately avoids a JSON dependency to keep its self-contained image small. The
 * protocol only ever exchanges flat objects with string/number/boolean values (the request may nest
 * a single {@code voice} object), so a tiny purpose-built reader and writer cover the whole surface
 * and stay trivially testable.
 */
final class Json {

  private Json() {}

  /** Writes the response header object the plugin reads before the PCM frame. */
  static String header(int sampleRate, int samples, String format) {
    return "{\"sampleRate\":"
        + sampleRate
        + ",\"samples\":"
        + samples
        + ",\"format\":\""
        + format
        + "\"}";
  }

  /** Writes an error object so a failed request still yields a parseable line on stdout. */
  static String error(String message) {
    return "{\"error\":\"" + escape(message == null ? "" : message) + "\"}";
  }

  /**
   * Parses one request line into a flat map. The nested {@code voice} object is flattened under the
   * keys {@code voice.race}, {@code voice.gender}, {@code voice.player}. Values are returned as
   * their raw token text (callers coerce to the type they need). Only the small fixed key set the
   * protocol uses is recognised.
   */
  static Map<String, String> parseRequest(String line) {
    Map<String, String> out = new HashMap<>();
    if (line == null) {
      return out;
    }
    Parser p = new Parser(line);
    p.skipWs();
    p.expect('{');
    parseObject(p, out, "");
    return out;
  }

  private static void parseObject(Parser p, Map<String, String> out, String prefix) {
    p.skipWs();
    if (p.peek() == '}') {
      p.next();
      return;
    }
    while (true) {
      p.skipWs();
      String key = p.readString();
      p.skipWs();
      p.expect(':');
      p.skipWs();
      char c = p.peek();
      if (c == '{') {
        p.next();
        parseObject(p, out, prefix + key + ".");
      } else if (c == '"') {
        out.put(prefix + key, p.readString());
      } else {
        out.put(prefix + key, p.readLiteral());
      }
      p.skipWs();
      char sep = p.next();
      if (sep == ',') {
        continue;
      }
      if (sep == '}') {
        return;
      }
      throw new IllegalArgumentException("Unexpected character '" + sep + "' in JSON object");
    }
  }

  private static String escape(String s) {
    StringBuilder b = new StringBuilder(s.length() + 8);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"':
          b.append("\\\"");
          break;
        case '\\':
          b.append("\\\\");
          break;
        case '\n':
          b.append("\\n");
          break;
        case '\r':
          b.append("\\r");
          break;
        case '\t':
          b.append("\\t");
          break;
        default:
          b.append(c);
      }
    }
    return b.toString();
  }

  /** Hand-rolled scanner over a single JSON line. */
  private static final class Parser {
    private final String s;
    private int i;

    Parser(String s) {
      this.s = s;
    }

    void skipWs() {
      while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
        i++;
      }
    }

    char peek() {
      if (i >= s.length()) {
        throw new IllegalArgumentException("Unexpected end of JSON");
      }
      return s.charAt(i);
    }

    char next() {
      char c = peek();
      i++;
      return c;
    }

    void expect(char c) {
      char actual = next();
      if (actual != c) {
        throw new IllegalArgumentException("Expected '" + c + "' but found '" + actual + "'");
      }
    }

    String readString() {
      expect('"');
      StringBuilder b = new StringBuilder();
      while (true) {
        char c = next();
        if (c == '"') {
          return b.toString();
        }
        if (c == '\\') {
          char e = next();
          switch (e) {
            case '"':
              b.append('"');
              break;
            case '\\':
              b.append('\\');
              break;
            case '/':
              b.append('/');
              break;
            case 'n':
              b.append('\n');
              break;
            case 'r':
              b.append('\r');
              break;
            case 't':
              b.append('\t');
              break;
            case 'b':
              b.append('\b');
              break;
            case 'f':
              b.append('\f');
              break;
            case 'u':
              String hex = s.substring(i, i + 4);
              i += 4;
              b.append((char) Integer.parseInt(hex, 16));
              break;
            default:
              throw new IllegalArgumentException("Invalid escape \\" + e);
          }
        } else {
          b.append(c);
        }
      }
    }

    /** Reads a bare literal (number, true, false, null) up to the next structural character. */
    String readLiteral() {
      int start = i;
      while (i < s.length()) {
        char c = s.charAt(i);
        if (c == ',' || c == '}' || c == ']' || Character.isWhitespace(c)) {
          break;
        }
        i++;
      }
      return s.substring(start, i);
    }
  }
}
