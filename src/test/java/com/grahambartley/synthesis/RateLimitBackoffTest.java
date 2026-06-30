package com.grahambartley.synthesis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.junit.Test;
import org.junit.runner.RunWith;

/** The cloud 429 back-off window growth and the throttle state transitions. */
@RunWith(JUnitParamsRunner.class)
public class RateLimitBackoffTest {

  @Test
  @Parameters({
    "1, 1000",
    "2, 2000",
    "3, 4000",
    "50, 30000",
  })
  public void windowGrowsGeometricallyAndCaps(int attempt, int expectedWindow) {
    assertEquals(expectedWindow, RateLimitBackoff.backoffWindowMillis(attempt));
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
