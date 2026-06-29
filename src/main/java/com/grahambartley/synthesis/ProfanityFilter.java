package com.grahambartley.synthesis;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/**
 * Offline, deterministic profanity masker. Loads a bundled wordlist once at construction into an
 * immutable lookup and a single precompiled tokenizer, so {@link #mask(String)} is a single linear
 * pass over the line with no per-call allocation of the matcher and no regex recompilation: cheap
 * enough to run inline on the voiced-line hot path without adding perceptible latency.
 *
 * <p>Matching is <em>whole-token</em> in a normalized space (lowercase, leetspeak folded, interior
 * separators stripped), so evasions like {@code f.u.c.k}, {@code sh1t}, and {@code @ss} resolve to
 * their base word, while lore words that merely <em>contain</em> a listed term ({@code Scunthorpe},
 * {@code assassin}, {@code Sussex}) never match. Matched tokens are replaced with asterisks of the
 * same visible length; surrounding text and the casing of clean words are untouched, and the output
 * contains no maskable tokens, so masking is idempotent.
 */
@Slf4j
public final class ProfanityFilter {

  private static final String WORDLIST_RESOURCE = "/profanity.txt";

  /**
   * A word-ish token: starts and ends with a letter, digit, or a letter-substituting leet symbol
   * ({@code @ $}), with interior letters, digits, and the common evasion separators {@code . _ - *}
   * allowed between. Including {@code @ $} at the edges catches edge evasions like {@code @ss} and
   * {@code a$$}; the asterisk is interior-only so the filter's own {@code ****} output is never
   * re-tokenized, keeping masking idempotent. Pure sentence punctuation at the edges stays outside
   * the token, so masking does not eat it.
   */
  private static final Pattern TOKEN =
      Pattern.compile("[\\p{L}\\p{N}@$](?:[\\p{L}\\p{N}._*@$-]*[\\p{L}\\p{N}@$])?");

  /**
   * Leetspeak fold applied during normalization, kept deliberately small so it cannot mangle clean
   * words into false positives. Index i maps {@code FROM[i] -> TO[i]}.
   */
  private static final char[] LEET_FROM = {'0', '1', '3', '4', '5', '7', '@', '$'};

  private static final char[] LEET_TO = {'o', 'i', 'e', 'a', 's', 't', 'a', 's'};

  /**
   * Normalized tokens that must never be masked even if a future blocklist entry would catch them.
   * Whole-token matching already spares substrings (Scunthorpe, assassin), so this is a thin extra
   * guard against an overly broad base word, biased toward not over-bleeping lore.
   */
  private static final Set<String> ALLOWLIST =
      Collections.unmodifiableSet(
          new HashSet<>(
              Arrays.asList(
                  "scunthorpe",
                  "assassin",
                  "assassinate",
                  "assassination",
                  "assess",
                  "assassins",
                  "sussex",
                  "penistone",
                  "class",
                  "pass",
                  "bass",
                  "grass",
                  "compass",
                  "cockle",
                  "shitake",
                  "dickens")));

  private final Set<String> blocklist;

  /** Loads the bundled wordlist. Falls back to an empty (pass-through) filter if it is missing. */
  public ProfanityFilter() {
    this.blocklist = loadBundled();
  }

  private Set<String> loadBundled() {
    Set<String> words = new HashSet<>();
    try (InputStream stream = getClass().getResourceAsStream(WORDLIST_RESOURCE)) {
      if (stream == null) {
        log.warn(
            "Profanity wordlist {} not found - profanity masking is a no-op this session",
            WORDLIST_RESOURCE);
        return Collections.emptySet();
      }
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          String entry = line.trim();
          if (entry.isEmpty() || entry.charAt(0) == '#') {
            continue;
          }
          String normalized = normalize(entry);
          if (!normalized.isEmpty()) {
            words.add(normalized);
          }
        }
      }
    } catch (Exception e) {
      log.error("Failed to load profanity wordlist {}: {}", WORDLIST_RESOURCE, e.getMessage());
      return Collections.emptySet();
    }
    return Collections.unmodifiableSet(words);
  }

  /**
   * Folds a token into the matching space: lowercase, leetspeak substituted, then every remaining
   * non-letter dropped, so {@code "F.U.C.K"}, {@code "sh1t"}, and {@code "@$$"} all collapse to
   * their letters-only base form.
   */
  static String normalize(String text) {
    StringBuilder out = new StringBuilder(text.length());
    String lower = text.toLowerCase(Locale.ROOT);
    for (int i = 0; i < lower.length(); i++) {
      char c = lower.charAt(i);
      char mapped = leet(c);
      if (mapped >= 'a' && mapped <= 'z') {
        out.append(mapped);
      }
    }
    return out.toString();
  }

  private static char leet(char c) {
    for (int i = 0; i < LEET_FROM.length; i++) {
      if (LEET_FROM[i] == c) {
        return LEET_TO[i];
      }
    }
    return c;
  }

  /**
   * Replaces every blocklisted token in {@code text} with same-length asterisks, leaving clean
   * words, casing, and punctuation exactly as they were. Null and blank input pass through
   * unchanged. Idempotent: the asterisk runs it produces contain no maskable tokens.
   */
  public String mask(String text) {
    if (text == null || text.isEmpty() || blocklist.isEmpty()) {
      return text;
    }
    Matcher matcher = TOKEN.matcher(text);
    StringBuilder out = null;
    int last = 0;
    while (matcher.find()) {
      String token = matcher.group();
      String normalized = normalize(token);
      if (normalized.isEmpty()
          || ALLOWLIST.contains(normalized)
          || !blocklist.contains(normalized)) {
        continue;
      }
      if (out == null) {
        out = new StringBuilder(text.length());
      }
      out.append(text, last, matcher.start());
      for (int i = matcher.start(); i < matcher.end(); i++) {
        out.append('*');
      }
      last = matcher.end();
    }
    if (out == null) {
      return text;
    }
    out.append(text, last, text.length());
    return out.toString();
  }
}
