package com.grahambartley.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.google.gson.Gson;
import java.nio.file.Files;
import java.util.concurrent.Executor;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** The learn coordinator: gating, one-attempt dedup, and writing wiki hits to the store. */
public class NpcLearningServiceTest {

  private static final Executor INLINE = Runnable::run;
  private final Gson gson = new Gson();

  private MockWebServer server;
  private WikiNpcClient client;
  private LearnedNpcStore store;

  @Before
  public void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
    client = new WikiNpcClient(new OkHttpClient(), gson, server.url("/api.php").toString());
    store = new LearnedNpcStore(Files.createTempDirectory("learn").resolve("l.json"), gson);
  }

  @After
  public void tearDown() throws Exception {
    server.shutdown();
  }

  private void enqueueNpc() {
    server.enqueue(
        new MockResponse()
            .setBody(
                "{\"query\":{\"pages\":[{\"revisions\":[{\"slots\":{\"main\":{\"content\":"
                    + "\"{{Infobox NPC\\n|race=[[Troll]]\\n|gender=Male\\n|id=1\\n}}\"}}}]}]}}"));
  }

  @Test
  public void learnsAnUnknownNpcThenDoesNotQueryAgain() {
    enqueueNpc();
    NpcLearningService service = new NpcLearningService(client, store, INLINE, () -> true);

    service.considerLearning(500, "New Troll");
    assertNotNull("the wiki hit is stored", store.get(500));
    assertEquals("Troll", store.get(500).getRace());
    assertEquals(1, server.getRequestCount());

    service.considerLearning(500, "New Troll");
    assertEquals("an id is attempted at most once", 1, server.getRequestCount());
  }

  @Test
  public void disabledMakesNoRequest() {
    NpcLearningService service = new NpcLearningService(client, store, INLINE, () -> false);
    service.considerLearning(600, "Someone");
    assertEquals(0, server.getRequestCount());
    assertNull(store.get(600));
  }

  @Test
  public void alreadyLearnedIdIsNotRefetched() {
    store.learn(700, "Human", "Female", null);
    NpcLearningService service = new NpcLearningService(client, store, INLINE, () -> true);
    service.considerLearning(700, "Known");
    assertEquals(0, server.getRequestCount());
  }
}
