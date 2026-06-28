package com.grahambartley.synthesis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.grahambartley.TTSDialogueConfig;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Request shape, system-prompt stability, content extraction, and graceful failure. */
public class OpenRouterTranslatorTest {

  private MockWebServer server;
  private OkHttpClient client;
  private final Gson gson = new Gson();
  private final TTSDialogueConfig config = new TTSDialogueConfig() {};

  @Before
  public void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
    client = new OkHttpClient();
  }

  @After
  public void tearDown() throws Exception {
    server.shutdown();
  }

  private OpenRouterTranslator translator() {
    return new OpenRouterTranslator(
        client, config, gson, server.url("/api/v1/chat/completions").toString());
  }

  private static String chatResponse(String content) {
    JsonObject message = new JsonObject();
    message.addProperty("role", "assistant");
    message.addProperty("content", content);
    JsonObject choice = new JsonObject();
    choice.add("message", message);
    JsonArray choices = new JsonArray();
    choices.add(choice);
    JsonObject body = new JsonObject();
    body.add("choices", choices);
    return body.toString();
  }

  @Test
  public void systemPromptIsStablePerLanguageAndNamesTheTarget() {
    assertEquals(
        "the same target yields a byte-identical prompt so the model's cache hits",
        OpenRouterTranslator.systemPrompt("French"),
        OpenRouterTranslator.systemPrompt("French"));
    assertNotEquals(
        OpenRouterTranslator.systemPrompt("French"), OpenRouterTranslator.systemPrompt("German"));
    assertTrue(OpenRouterTranslator.systemPrompt("French").contains("French"));
  }

  @Test
  public void translatesAndReturnsTrimmedContent() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(200).setBody(chatResponse("  Bonjour  ")));

    String result = translator().translate("Hello", "French", "sk-or-abc");

    assertEquals("Bonjour", result);

    RecordedRequest recorded = server.takeRequest();
    assertEquals("POST", recorded.getMethod());
    assertEquals("Bearer sk-or-abc", recorded.getHeader("Authorization"));
    JsonObject body = new JsonParser().parse(recorded.getBody().readUtf8()).getAsJsonObject();
    assertEquals("google/gemini-3.1-flash-lite-preview", body.get("model").getAsString());
    assertEquals(
        "throughput routing applies to the translation hop too",
        "throughput",
        body.getAsJsonObject("provider").get("sort").getAsString());
    JsonArray messages = body.getAsJsonArray("messages");
    assertEquals("system prompt then user line", 2, messages.size());
    assertEquals("system", messages.get(0).getAsJsonObject().get("role").getAsString());
    assertEquals(
        "the per-line text is the user message, keeping the system prefix stable",
        "Hello",
        messages.get(1).getAsJsonObject().get("content").getAsString());
  }

  @Test
  public void nonSuccessReturnsNull() {
    server.enqueue(new MockResponse().setResponseCode(429).setBody("rate limited"));
    assertNull(translator().translate("Hello", "French", "sk-or-abc"));
  }

  @Test
  public void unparseableOrEmptyBodyReturnsNull() {
    server.enqueue(new MockResponse().setResponseCode(200).setBody("not json"));
    assertNull(translator().translate("Hello", "French", "sk-or-abc"));
  }

  @Test
  public void emptyInputIsReturnedWithoutCallingTheNetwork() {
    assertEquals("", translator().translate("", "French", "sk-or-abc"));
    assertEquals("no HTTP request for empty input", 0, server.getRequestCount());
  }
}
