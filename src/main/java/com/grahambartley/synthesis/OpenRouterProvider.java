package com.grahambartley.synthesis;

import com.google.gson.JsonObject;

/**
 * Builds the shared OpenRouter {@code provider} preferences block applied to every OpenRouter call
 * (TTS and translation), so routing behaves identically across call sites.
 *
 * <p>{@code sort: "throughput"} routes each request to the lowest-latency provider for the model
 * (the same effect as the {@code :nitro} model-slug shortcut, kept in the body rather than mangling
 * the fixed model id).
 */
final class OpenRouterProvider {

  /** Routes to the fastest provider for the model (equivalent to the {@code :nitro} suffix). */
  static final String THROUGHPUT_SORT = "throughput";

  private OpenRouterProvider() {}

  /** Attaches the {@code provider} preferences to a request body, pinning throughput routing. */
  static void apply(JsonObject body) {
    JsonObject provider = new JsonObject();
    provider.addProperty("sort", THROUGHPUT_SORT);
    body.add("provider", provider);
  }
}
