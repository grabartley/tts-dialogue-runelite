package com.grahambartley.synthesis;

import com.google.gson.JsonObject;

/**
 * Builds the shared OpenRouter {@code provider} preferences block applied to every OpenRouter call
 * (TTS and translation), so routing behaves identically across call sites.
 *
 * <p>{@code sort: "throughput"} routes each request to the lowest-latency provider for the model
 * (the same effect as the {@code :nitro} model-slug shortcut, kept in the body so it composes with
 * the optional region bias instead of mangling the fixed model id). When a non-blank region is
 * configured it is added alongside as a geographic bias; a blank region omits the field entirely so
 * the default request body is unchanged and OpenRouter picks the provider on its own.
 */
final class OpenRouterProvider {

  /** Routes to the fastest provider for the model (equivalent to the {@code :nitro} suffix). */
  static final String THROUGHPUT_SORT = "throughput";

  private OpenRouterProvider() {}

  /**
   * Attaches the {@code provider} preferences to a request body. Always pins throughput routing;
   * adds the region bias only when {@code region} is non-blank.
   */
  static void apply(JsonObject body, String region) {
    JsonObject provider = new JsonObject();
    provider.addProperty("sort", THROUGHPUT_SORT);
    if (region != null && !region.trim().isEmpty()) {
      provider.addProperty("region", region.trim());
    }
    body.add("provider", provider);
  }
}
