package com.grahambartley.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.google.gson.Gson;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** The runtime wiki NPC lookup: infobox parsing, race/region mapping, and graceful misses. */
public class WikiNpcClientTest {

  private MockWebServer server;
  private WikiNpcClient client;

  @Before
  public void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
    client = new WikiNpcClient(new OkHttpClient(), new Gson(), server.url("/api.php").toString());
  }

  @After
  public void tearDown() throws Exception {
    server.shutdown();
  }

  private static String pageBody(String infobox) {
    String content = infobox.replace("\n", "\\n").replace("\"", "\\\"");
    return "{\"query\":{\"pages\":[{\"title\":\"X\",\"revisions\":[{\"slots\":{\"main\":{\"content\":\""
        + content
        + "\"}}}]}]}}";
  }

  @Test
  public void parsesRaceGenderAndDesertRegion() {
    server.enqueue(
        new MockResponse()
            .setBody(
                pageBody(
                    "{{Infobox NPC\n|race = [[Human]]\n|gender = Female\n|leagueRegion = Desert\n"
                        + "|location = Pollnivneach\n|id = 123\n}}")));

    NPCAttributes a = client.lookup("Some Trader");
    assertEquals("Human", a.getRace());
    assertEquals("Female", a.getGender());
    assertEquals("kharidian", a.getRegion());
    assertEquals("Wiki", a.getSource());
  }

  @Test
  public void menaphiteLocationMapsToEgyptianRegion() {
    server.enqueue(
        new MockResponse()
            .setBody(
                pageBody(
                    "{{Infobox NPC\n|race=[[Human]]\n|gender=Male\n|leagueRegion=Desert\n"
                        + "|location=Sophanem\n|id=1\n}}")));
    assertEquals("menaphite", client.lookup("Sophanem Guard").getRegion());
  }

  @Test
  public void mapsLoreRaceOntoVoiceBucket() {
    server.enqueue(
        new MockResponse()
            .setBody(pageBody("{{Infobox NPC\n|race=[[Ogre]]\n|gender=Male\n|id=1\n}}")));
    assertEquals("an ogre voices from the Troll bucket", "Troll", client.lookup("Ogre").getRace());
  }

  @Test
  public void gnomesAreTheirOwnRace() {
    server.enqueue(
        new MockResponse()
            .setBody(pageBody("{{Infobox NPC\n|race=[[Gnome]]\n|gender=Male\n|id=1\n}}")));
    assertEquals(
        "gnomes get their own race so they can sound Irish",
        "Gnome",
        client.lookup("Gnome").getRace());
  }

  @Test
  public void aPageWithoutAnInfoboxRaceIsAMiss() {
    server.enqueue(new MockResponse().setBody("{\"query\":{\"pages\":[{\"missing\":true}]}}"));
    assertNull(client.lookup("Not An NPC"));
  }

  @Test
  public void nonSuccessIsAMiss() {
    server.enqueue(new MockResponse().setResponseCode(500));
    assertNull(client.lookup("Anything"));
  }

  @Test
  public void multiRegionNpcCarriesNoSingleRegion() {
    assertNull(WikiNpcClient.regionKey("Desert, Misthalin", null));
    assertNull(WikiNpcClient.regionKey("No", null));
    assertEquals("fremennik", WikiNpcClient.regionKey("Fremennik", null));
    assertEquals("varlamore", WikiNpcClient.regionKey("Varlamore", null));
  }

  @Test
  public void raceBucketMappingMirrorsTheGenerator() {
    assertEquals("Undead", WikiNpcClient.bucketForRace("Vampyre"));
    assertEquals("Demon", WikiNpcClient.bucketForRace("Dragon"));
    assertEquals("Troll", WikiNpcClient.bucketForRace("Ogre"));
    assertEquals("Human", WikiNpcClient.bucketForRace("Something unknown"));
  }
}
