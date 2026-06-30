package com.grahambartley.synthesis;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The standardized one-line cloud synth trace shape (success, retry, failure). */
public class CloudSynthTraceTest {

  @Test
  public void successCarriesAttemptTimingAndSize() {
    String line = CloudSynthTrace.success(1, 2, 412, 83, 51200, "gen-abc");
    assertTrue(line, line.startsWith("[TTS cloud] synth ok"));
    assertTrue(line, line.contains("attempt=1/2"));
    assertTrue(line, line.contains("elapsedMs=412"));
    assertTrue(line, line.contains("inputLen=83"));
    assertTrue(line, line.contains("bytes=51200"));
    assertTrue(line, line.contains("genId=gen-abc"));
  }

  @Test
  public void retryRecordsReasonAttemptAndElapsed() {
    String line = CloudSynthTrace.retry("empty-body", 1, 2, 120);
    assertTrue(line, line.startsWith("[TTS cloud] synth retry"));
    assertTrue(line, line.contains("reason=empty-body"));
    assertTrue(line, line.contains("attempt=1/2"));
    assertTrue(line, line.contains("elapsedMs=120"));
  }

  @Test
  public void httpFailureCarriesEveryField() {
    String line =
        CloudSynthTrace.failure(
            "non-2xx", 1, 2, 95, 83, 429, "application/json", "gen-abc", 42, "Too Many Requests");
    assertTrue(line, line.startsWith("[TTS cloud] synth fail"));
    assertTrue(line, line.contains("reason=non-2xx"));
    assertTrue(line, line.contains("attempt=1/2"));
    assertTrue(line, line.contains("elapsedMs=95"));
    assertTrue(line, line.contains("inputLen=83"));
    assertTrue(line, line.contains("http=429"));
    assertTrue(line, line.contains("contentType=application/json"));
    assertTrue(line, line.contains("genId=gen-abc"));
    assertTrue(line, line.contains("bytes=42"));
    assertTrue(line, line.contains("detail=Too Many Requests"));
  }

  @Test
  public void networkFailureCollapsesAbsentHttpFieldsToDash() {
    String line = CloudSynthTrace.failure("network", 2, 2, 2000, 83, 0, "", "", 0, "timeout");
    assertTrue(line, line.contains("reason=network"));
    assertTrue(line, line.contains("http=-"));
    assertTrue(line, line.contains("contentType=-"));
    assertTrue(line, line.contains("genId=-"));
    assertTrue(line, line.contains("detail=timeout"));
  }

  @Test
  public void traceNeverLeaksDialogueText() {
    // Only the length is carried, never the words, so the lines are safe to emit ungated.
    String dialogue = "Greetings adventurer, the king needs your help.";
    String success = CloudSynthTrace.success(1, 2, 10, dialogue.length(), 100, "g");
    String failure =
        CloudSynthTrace.failure(
            "truncated", 2, 2, 10, dialogue.length(), 200, "audio/pcm", "g", 5, "OK");
    assertFalse(success, success.contains(dialogue));
    assertFalse(failure, failure.contains(dialogue));
  }
}
