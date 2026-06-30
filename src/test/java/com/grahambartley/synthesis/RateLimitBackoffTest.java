package com.grahambartley.synthesis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The cloud 429 back-off window growth and the throttle state transitions. */
public class RateLimitBackoffTest {

  @Test
  public void windowGrowsGeometricallyAndCaps() {
    assertEquals(
        "first 429 backs off the base window", 1_000, RateLimitBackoff.backoffWindowMillis(1));
    assertEquals("second doubles", 2_000, RateLimitBackoff.backoffWindowMillis(2));
    assertEquals("third doubles again", 4_000, RateLimitBackoff.backoffWindowMillis(3));
    assertEquals(
        "the window is capped so it never grows without bound",
        30_000,
        RateLimitBackoff.backoffWindowMillis(50));
  }

  @Test
  public void freshBackoffIsNotThrottled() {
    assertFalse(new RateLimitBackoff().isThrottled());
  }

  @Test
  public void aRateLimitThrottlesAndACleanCallClears() {
    RateLimitBackoff backoff = new RateLimitBackoff();
    backoff.recordRateLimited();
    assertTrue("a 429 opens a back-off window", backoff.isThrottled());
    backoff.recordSuccess();
    assertFalse("a clean call clears the back-off", backoff.isThrottled());
  }
}
