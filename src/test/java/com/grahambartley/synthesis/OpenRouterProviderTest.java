package com.grahambartley.synthesis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import org.junit.Test;

/** The shared provider preferences block: throughput sort always, region only when set. */
public class OpenRouterProviderTest {

  @Test
  public void alwaysPinsThroughputSort() {
    JsonObject body = new JsonObject();
    OpenRouterProvider.apply(body, "");

    JsonObject provider = body.getAsJsonObject("provider");
    assertEquals(
        "every call routes to the fastest provider (the :nitro equivalent)",
        "throughput",
        provider.get("sort").getAsString());
  }

  @Test
  public void blankRegionOmitsTheRegionField() {
    JsonObject blank = new JsonObject();
    OpenRouterProvider.apply(blank, "");
    assertFalse(
        "a blank region adds no region field", blank.getAsJsonObject("provider").has("region"));

    JsonObject whitespace = new JsonObject();
    OpenRouterProvider.apply(whitespace, "   ");
    assertFalse(
        "a whitespace-only region adds no region field",
        whitespace.getAsJsonObject("provider").has("region"));

    JsonObject nullRegion = new JsonObject();
    OpenRouterProvider.apply(nullRegion, null);
    assertFalse(
        "a null region adds no region field", nullRegion.getAsJsonObject("provider").has("region"));
  }

  @Test
  public void nonBlankRegionIsInjectedTrimmed() {
    JsonObject body = new JsonObject();
    OpenRouterProvider.apply(body, "  eu  ");

    JsonObject provider = body.getAsJsonObject("provider");
    assertTrue("a set region is present in the provider block", provider.has("region"));
    assertEquals("the region is trimmed", "eu", provider.get("region").getAsString());
  }
}
