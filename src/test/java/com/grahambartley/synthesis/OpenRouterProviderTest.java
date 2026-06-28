package com.grahambartley.synthesis;

import static org.junit.Assert.assertEquals;

import com.google.gson.JsonObject;
import org.junit.Test;

/** The shared provider preferences block pins throughput routing on every call. */
public class OpenRouterProviderTest {

  @Test
  public void alwaysPinsThroughputSort() {
    JsonObject body = new JsonObject();
    OpenRouterProvider.apply(body);

    JsonObject provider = body.getAsJsonObject("provider");
    assertEquals(
        "every call routes to the fastest provider (the :nitro equivalent)",
        "throughput",
        provider.get("sort").getAsString());
  }
}
