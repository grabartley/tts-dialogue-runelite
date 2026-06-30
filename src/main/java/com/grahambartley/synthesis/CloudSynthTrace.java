package com.grahambartley.synthesis;

/**
 * Formats the one-line cloud synthesis trace records (success, retry, failure) into a single,
 * consistent {@code key=value} shape so a grep over {@code [TTS cloud] synth} gives every attempt's
 * outcome, timing, and failure reason. Pure string building, kept out of {@link
 * OpenRouterTtsBackend} so the shape is verifiable without a live HTTP call or logger.
 *
 * <p>Every record carries the attempt number, elapsed ms, and (where known) the input length, so a
 * slow or failing line can be quantified rather than guessed at. The dialogue text itself is never
 * included, only its length, so these lines are safe to emit ungated; the response body snippet
 * (which can echo an error payload) stays behind debug mode at the call site.
 */
final class CloudSynthTrace {

  private CloudSynthTrace() {}

  /** A line was synthesized cleanly on this attempt. */
  static String success(
      int attempt, int maxAttempts, long elapsedMs, int inputLen, int byteCount, String genId) {
    return "[TTS cloud] synth ok attempt="
        + attempt
        + "/"
        + maxAttempts
        + " elapsedMs="
        + elapsedMs
        + " inputLen="
        + inputLen
        + " bytes="
        + byteCount
        + " genId="
        + orDash(genId);
  }

  /** This attempt failed transiently and is being retried; the next attempt is timed separately. */
  static String retry(String reason, int attempt, int maxAttempts, long elapsedMs) {
    return "[TTS cloud] synth retry reason="
        + reason
        + " attempt="
        + attempt
        + "/"
        + maxAttempts
        + " elapsedMs="
        + elapsedMs;
  }

  /**
   * A line was abandoned on this attempt. Standardized across every failure path (non-2xx,
   * empty-body, undecodable, truncated, network, unexpected) so they read identically: HTTP fields
   * collapse to {@code -} when there was no response (network/unexpected), and {@code detail}
   * carries the HTTP message or the exception message.
   */
  static String failure(
      String reason,
      int attempt,
      int maxAttempts,
      long elapsedMs,
      int inputLen,
      int httpStatus,
      String contentType,
      String genId,
      int byteCount,
      String detail) {
    return "[TTS cloud] synth fail reason="
        + reason
        + " attempt="
        + attempt
        + "/"
        + maxAttempts
        + " elapsedMs="
        + elapsedMs
        + " inputLen="
        + inputLen
        + " http="
        + (httpStatus <= 0 ? "-" : Integer.toString(httpStatus))
        + " contentType="
        + orDash(contentType)
        + " genId="
        + orDash(genId)
        + " bytes="
        + byteCount
        + " detail="
        + orDash(detail);
  }

  private static String orDash(String value) {
    return value == null || value.isEmpty() ? "-" : value;
  }
}
