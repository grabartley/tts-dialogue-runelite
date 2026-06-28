package com.grahambartley.data;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Looks an NPC's race, gender and home region up on the Old School RuneScape Wiki at runtime, for
 * NPCs missing from the bundled table (typically ones added to the game since the last plugin
 * update). It queries the MediaWiki API for the NPC's page lead wikitext and parses the {@code
 * Infobox NPC} fields, mirroring the offline generator ({@code tools/generate_npc_voices.py}) so a
 * learned NPC sounds the same as a baked-in one.
 *
 * <p>This runs only on a background thread (never the game thread), through the injected {@link
 * OkHttpClient}. Every failure path (network error, missing page, no infobox, unparsable body)
 * returns {@code null} rather than throwing, so a lookup miss simply leaves the NPC on the default
 * voice.
 */
@Slf4j
public final class WikiNpcClient {

  private static final String PRODUCTION_API = "https://oldschool.runescape.wiki/api.php";
  private static final String USER_AGENT = "tts-dialogue-runelite";
  private static final Duration CALL_TIMEOUT = Duration.ofSeconds(8);

  private static final Pattern RACE = field("race");
  private static final Pattern GENDER = field("gender");
  private static final Pattern LEAGUE_REGION = field("leagueRegion");
  private static final Pattern LOCATION = field("location");
  private static final Pattern MENAPHITE =
      Pattern.compile("sophanem|menaphos|menaphite|necropolis", Pattern.CASE_INSENSITIVE);

  private static Pattern field(String key) {
    return Pattern.compile("\\|\\s*" + key + "\\d*\\s*=\\s*([^\\n|]+)", Pattern.CASE_INSENSITIVE);
  }

  private final OkHttpClient httpClient;
  private final Gson gson;
  private final String api;

  public WikiNpcClient(OkHttpClient httpClient, Gson gson) {
    this(httpClient, gson, PRODUCTION_API);
  }

  WikiNpcClient(OkHttpClient httpClient, Gson gson, String api) {
    this.httpClient = httpClient.newBuilder().callTimeout(CALL_TIMEOUT).build();
    this.gson = gson;
    this.api = api;
  }

  /**
   * Resolves race/gender/region for an NPC by wiki page name, or {@code null} when it cannot be
   * found or parsed. The returned attributes carry source {@code "Wiki"}.
   */
  public NPCAttributes lookup(String npcName) {
    if (npcName == null || npcName.trim().isEmpty()) {
      return null;
    }
    HttpUrl url =
        HttpUrl.get(api)
            .newBuilder()
            .addQueryParameter("action", "query")
            .addQueryParameter("prop", "revisions")
            .addQueryParameter("rvprop", "content")
            .addQueryParameter("rvslots", "main")
            .addQueryParameter("rvsection", "0")
            .addQueryParameter("redirects", "1")
            .addQueryParameter("format", "json")
            .addQueryParameter("formatversion", "2")
            .addQueryParameter("titles", npcName.trim())
            .build();
    Request request =
        new Request.Builder().url(url).addHeader("User-Agent", USER_AGENT).get().build();

    try (Response response = httpClient.newCall(request).execute()) {
      if (!response.isSuccessful()) {
        return null;
      }
      ResponseBody body = response.body();
      String wikitext = extractWikitext(body == null ? null : body.string());
      if (wikitext == null) {
        return null;
      }
      String race = bucketForRace(firstField(RACE, wikitext));
      if (race == null) {
        return null; // no Infobox NPC race -> not a usable NPC page (disambiguation, monster, ...)
      }
      String gender = normaliseGender(firstField(GENDER, wikitext));
      String region =
          regionKey(firstField(LEAGUE_REGION, wikitext), firstField(LOCATION, wikitext));
      NPCAttributes attributes = new NPCAttributes(race, gender, "Wiki", 0.9);
      attributes.setName(npcName);
      attributes.setRegion(region);
      return attributes;
    } catch (Exception e) {
      log.debug("Wiki lookup for '{}' failed: {}", npcName, e.getMessage());
      return null;
    }
  }

  private String extractWikitext(String json) {
    if (json == null) {
      return null;
    }
    try {
      JsonObject root = new JsonParser().parse(json).getAsJsonObject();
      JsonArray pages = root.getAsJsonObject("query").getAsJsonArray("pages");
      if (pages.size() == 0) {
        return null;
      }
      JsonObject page = pages.get(0).getAsJsonObject();
      if (!page.has("revisions")) {
        return null;
      }
      return page.getAsJsonArray("revisions")
          .get(0)
          .getAsJsonObject()
          .getAsJsonObject("slots")
          .getAsJsonObject("main")
          .get("content")
          .getAsString();
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static String firstField(Pattern pattern, String wikitext) {
    Matcher m = pattern.matcher(wikitext);
    if (!m.find()) {
      return null;
    }
    String value = m.group(1);
    value = value.replaceAll("<ref[^>]*>.*?</ref>", "");
    value = value.replaceAll("<[^>]+>", "");
    value = value.replace("[[", "").replace("]]", "");
    value = value.replaceAll("\\{\\{[^}]*\\}\\}", "");
    return value.trim();
  }

  /** Maps wiki race text onto a voice bucket; mirrors RACE_BUCKET_RULES in the generator. */
  static String bucketForRace(String raceText) {
    if (raceText == null || raceText.isEmpty()) {
      return null;
    }
    String t = raceText.toLowerCase(Locale.ROOT);
    if (t.matches(
        ".*(vampyre|vampire|\\bvyre\\b|zombie|skeleton|ghost|ghoul|undead|wight|shade|revenant|"
            + "mummy|banshee|spectre|wraith|ankou|lich|reanimat).*")) {
      return "Undead";
    }
    if (t.matches(
        ".*(demon|devil|\\bimp\\b|abyssal|dragon|wyvern|wyrm|drake|tzhaar|tztok|tzkal).*")) {
      return "Demon";
    }
    if (t.matches(".*gnome.*")) {
      return "Gnome";
    }
    if (t.matches(".*(goblin|hobgoblin).*")) {
      return "Goblin";
    }
    if (t.matches(".*(dwarf|dwarven).*")) {
      return "Dwarf";
    }
    if (t.matches(".*(\\belf\\b|\\belves\\b|elven).*")) {
      return "Elf";
    }
    if (t.matches(".*(troll|\\bgiant\\b|cyclops|ogre|\\bent\\b|\\bgolem\\b).*")) {
      return "Troll";
    }
    if (t.matches(".*(wizard|sorcerer|sorceress|necromancer|\\bmage\\b).*")) {
      return "Wizard";
    }
    if (t.matches(".*(\\bhuman\\b|\\bman\\b|\\bwoman\\b).*")) {
      return "Human";
    }
    return "Human";
  }

  private static String normaliseGender(String genderText) {
    if (genderText != null) {
      String g = genderText.trim().toLowerCase(Locale.ROOT);
      if (g.startsWith("f")) {
        return "Female";
      }
      if (g.startsWith("m")) {
        return "Male";
      }
    }
    return "Male";
  }

  /** Maps a single wiki leagueRegion onto a region accent key; mirrors the generator. */
  static String regionKey(String leagueRegion, String location) {
    if (leagueRegion == null) {
      return null;
    }
    String lr = leagueRegion.trim();
    if (lr.contains(",") || lr.contains("&")) {
      return null; // documented in several regions -> no single home accent
    }
    switch (lr.toLowerCase(Locale.ROOT)) {
      case "desert":
        return location != null && MENAPHITE.matcher(location).find() ? "menaphite" : "kharidian";
      case "misthalin":
        return "misthalin";
      case "asgarnia":
        return "asgarnia";
      case "kandarin":
        return "kandarin";
      case "kourend":
        return "kourend";
      case "wilderness":
        return "wilderness";
      case "tirannwn":
        return "tirannwn";
      case "varlamore":
        return "varlamore";
      case "karamja":
        return "karamja";
      case "morytania":
        return "morytania";
      case "fremennik":
        return "fremennik";
      default:
        return null;
    }
  }
}
