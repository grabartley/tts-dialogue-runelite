package com.grahambartley.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.grahambartley.synthesis.CharacterProfile;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Loads and resolves the character voice profiles bundled in {@code /npc-voices.json} under the
 * top-level {@code profiles} key (produced offline by {@code tools/generate_npc_voices.py} from
 * {@code tools/profiles.json}).
 *
 * <p>Resolution <em>combines</em> every matching layer: {@code default} (always complete), {@code
 * byRace[race]}, {@code byEthnicity[ethnicity]}, <em>every</em> {@code byCategory} entry whose
 * keyword word-matches the display name, and {@code byId[npcId]}. An NPC can be several things at
 * once (a Fremennik human, a ghost pirate), so all matches contribute: {@code style} accumulates
 * across the layers, while {@code name}, {@code accent}, and {@code pace} take the most specific
 * layer that sets them. A bespoke per-NPC entry therefore need only carry what is unique (usually a
 * {@code name} and a {@code style}); the rest falls through to the category, race, or British
 * default. Every NPC resolves to a complete {@link CharacterProfile}, whether or not it has a
 * bespoke entry.
 *
 * <p>The player is resolved separately: the {@code player} layer over the default, with the three
 * configured player fields (accent/style/pace) overriding when non-blank.
 */
@Slf4j
public final class NpcProfileTable {

  private static final String TABLE_RESOURCE = "/npc-voices.json";

  /**
   * Last-resort British default used only when the bundled {@code profiles.default} is missing or
   * incomplete, so resolution never returns {@code null} or an NPE even on a malformed resource.
   */
  private static final CharacterProfile BUILTIN_DEFAULT =
      new CharacterProfile(
          "Gielinor Commoner",
          "British English, Received Pronunciation, as heard in southern England.",
          "A grounded medieval fantasy townsperson; plain, sincere, and natural.",
          "Steady and conversational.");

  /** The resolved profile plus the layer that won, for debug logging. */
  public record Resolution(CharacterProfile profile, String source) {}

  /**
   * A sparse profile layer: any field may be {@code null}, meaning "inherit from the layer below".
   */
  private record Layer(String name, String accent, String style, String pace) {}

  /** An ordered keyword rule: the layer applies when any keyword word-matches the display name. */
  private record CategoryRule(String id, List<String> keywords, Layer layer) {}

  private CharacterProfile defaultProfile = BUILTIN_DEFAULT;
  private Layer playerLayer = null;
  private Map<String, Layer> byRace = Collections.emptyMap();
  private Map<String, Layer> byEthnicity = Collections.emptyMap();
  private List<CategoryRule> byCategory = Collections.emptyList();
  private Map<Integer, Layer> byId = Collections.emptyMap();
  private boolean loaded = false;

  /** Loads the {@code profiles} section from the bundled resource. */
  public void initialize() {
    try (InputStream stream = getClass().getResourceAsStream(TABLE_RESOURCE)) {
      if (stream == null) {
        log.warn(
            "NPC profile table {} not found - using the built-in British default for every line",
            TABLE_RESOURCE);
        return;
      }
      JsonObject root =
          new JsonParser()
              .parse(new InputStreamReader(stream, StandardCharsets.UTF_8))
              .getAsJsonObject();
      if (!root.has("profiles") || !root.get("profiles").isJsonObject()) {
        log.warn(
            "NPC profile table {} has no 'profiles' object - using the built-in British default",
            TABLE_RESOURCE);
        return;
      }
      parseProfiles(root.getAsJsonObject("profiles"));
      loaded = true;
      log.info(
          "NPC profiles loaded: {} race, {} ethnicity, {} keyword categories, {} bespoke NPC"
              + " overrides",
          byRace.size(),
          byEthnicity.size(),
          byCategory.size(),
          byId.size());
    } catch (Exception e) {
      log.error("Failed to load NPC profile table {}: {}", TABLE_RESOURCE, e.getMessage());
    }
  }

  /** Test seam: build a table directly from a parsed {@code profiles} object. */
  static NpcProfileTable fromProfilesJson(JsonObject profiles) {
    NpcProfileTable table = new NpcProfileTable();
    table.parseProfiles(profiles);
    table.loaded = true;
    return table;
  }

  private void parseProfiles(JsonObject profiles) {
    CharacterProfile parsedDefault = parseComplete(optObject(profiles, "default"));
    if (parsedDefault != null) {
      this.defaultProfile = parsedDefault;
    } else {
      log.warn("profiles.default is missing or incomplete - using the built-in British default");
    }

    this.playerLayer = parseLayer(optObject(profiles, "player"));

    this.byRace = parseLayerMap(optObject(profiles, "byRace"));
    this.byEthnicity = parseLayerMap(optObject(profiles, "byEthnicity"));

    List<CategoryRule> categories = new ArrayList<>();
    if (profiles.has("byCategory") && profiles.get("byCategory").isJsonArray()) {
      JsonArray arr = profiles.getAsJsonArray("byCategory");
      for (JsonElement el : arr) {
        if (!el.isJsonObject()) {
          continue;
        }
        JsonObject entry = el.getAsJsonObject();
        if (!entry.has("keywords") || !entry.get("keywords").isJsonArray()) {
          continue;
        }
        List<String> keywords = new ArrayList<>();
        for (JsonElement kw : entry.getAsJsonArray("keywords")) {
          keywords.add(kw.getAsString().toLowerCase(Locale.ROOT));
        }
        String id = entry.has("id") ? entry.get("id").getAsString() : "category";
        categories.add(new CategoryRule(id, keywords, parseLayer(entry)));
      }
    }
    this.byCategory = Collections.unmodifiableList(categories);

    Map<Integer, Layer> ids = new HashMap<>();
    JsonObject idObj = optObject(profiles, "byId");
    if (idObj != null) {
      for (String key : idObj.keySet()) {
        if (isComment(key) || !idObj.get(key).isJsonObject()) {
          continue;
        }
        try {
          ids.put(Integer.parseInt(key), parseLayer(idObj.getAsJsonObject(key)));
        } catch (NumberFormatException e) {
          log.warn("Skipping non-numeric byId profile key '{}'", key);
        }
      }
    }
    this.byId = Collections.unmodifiableMap(ids);
  }

  /**
   * Resolves the complete profile for an NPC by <em>combining</em> every matching layer: the race
   * bucket, the ethnicity accent, every keyword category whose keyword is in the display name, and
   * the per-NPC override. An NPC can be more than one thing at once (a Fremennik human, a ghost
   * pirate), so all matches contribute. {@code style} accumulates across every contributing layer
   * so the persona blends; {@code name}, {@code accent}, and {@code pace} are single-valued, so the
   * most specific layer that sets each one wins (per-NPC override, then the last matching category,
   * then race, then the default), which keeps a coherent accent and pace rather than stacking
   * contradictory directions. Never returns {@code null}.
   *
   * @param npcId the live NPC id, or {@code null} when unknown (no bespoke override is applied)
   * @param npcName the display name used for keyword matching, may be {@code null}
   * @param race the resolved race bucket (e.g. {@code "Troll"}), may be {@code null}
   * @param ethnicity the NPC's ethnicity accent key (e.g. {@code "kharidian"}), may be {@code null}
   */
  public Resolution resolveNpc(Integer npcId, String npcName, String race, String ethnicity) {
    // Contributing layers, least specific first, so a later layer wins single-valued fields.
    List<Layer> layers = new ArrayList<>();
    List<String> sources = new ArrayList<>();

    Layer raceLayer = race == null ? null : byRace.get(race.toLowerCase(Locale.ROOT));
    if (raceLayer != null) {
      layers.add(raceLayer);
      sources.add("race:" + race);
    }
    // Ethnicity tints only the plain folk (Human / unknown race); a distinctive race keeps its own
    // accent wherever it is found, so a dwarf stays gruff and Scottish even in the desert.
    boolean plainRace =
        race == null || race.equalsIgnoreCase("Human") || race.equalsIgnoreCase("Unknown");
    Layer ethnicityLayer =
        (ethnicity == null || !plainRace)
            ? null
            : byEthnicity.get(ethnicity.toLowerCase(Locale.ROOT));
    if (ethnicityLayer != null) {
      layers.add(ethnicityLayer);
      sources.add("ethnicity:" + ethnicity);
    }
    for (CategoryRule rule : matchCategories(npcName)) {
      layers.add(rule.layer());
      sources.add("keyword:" + rule.id());
    }
    Layer idLayer = npcId == null ? null : byId.get(npcId);
    if (idLayer != null) {
      layers.add(idLayer);
      sources.add("id:" + npcId);
    }

    String name = defaultProfile.name();
    String accent = defaultProfile.accent();
    String pace = defaultProfile.pace();
    List<String> styleParts = new ArrayList<>();
    for (Layer layer : layers) {
      if (layer.name() != null) {
        name = layer.name();
      }
      if (layer.accent() != null) {
        accent = layer.accent();
      }
      if (layer.pace() != null) {
        pace = layer.pace();
      }
      if (layer.style() != null) {
        styleParts.add(layer.style());
      }
    }
    String style = styleParts.isEmpty() ? defaultProfile.style() : String.join(" ", styleParts);
    String source = sources.isEmpty() ? "default" : String.join("+", sources);

    return new Resolution(new CharacterProfile(name, accent, style, pace), source);
  }

  /**
   * Resolves the player's profile: the {@code player} layer over the default, then the three
   * configured fields overriding when non-blank. The player's name label is never overridden by
   * config.
   */
  public CharacterProfile resolvePlayer(String accent, String style, String pace) {
    CharacterProfile base = apply(defaultProfile, playerLayer);
    return new CharacterProfile(
        base.name(),
        isBlank(accent) ? base.accent() : accent.trim(),
        isBlank(style) ? base.style() : style.trim(),
        isBlank(pace) ? base.pace() : pace.trim());
  }

  /** Whether the bundled {@code profiles} section loaded successfully. */
  public boolean isLoaded() {
    return loaded;
  }

  /** Every category whose keyword is in the display name, in declaration order (may be empty). */
  private List<CategoryRule> matchCategories(String npcName) {
    if (npcName == null || npcName.isEmpty()) {
      return Collections.emptyList();
    }
    String lower = npcName.toLowerCase(Locale.ROOT);
    List<CategoryRule> matches = new ArrayList<>();
    for (CategoryRule rule : byCategory) {
      for (String keyword : rule.keywords()) {
        if (wordContains(lower, keyword)) {
          matches.add(rule);
          break;
        }
      }
    }
    return matches;
  }

  /**
   * Whether {@code haystack} contains {@code needle} bounded by non-letters on both sides, so
   * {@code "imp"} matches "Imp" and "Imp Catcher" but not "important", and a hyphen counts as a
   * boundary. Both arguments are expected already lower-cased.
   */
  static boolean wordContains(String haystack, String needle) {
    if (needle.isEmpty()) {
      return false;
    }
    int idx = haystack.indexOf(needle);
    while (idx >= 0) {
      boolean leftOk = idx == 0 || !Character.isLetter(haystack.charAt(idx - 1));
      int end = idx + needle.length();
      boolean rightOk = end == haystack.length() || !Character.isLetter(haystack.charAt(end));
      if (leftOk && rightOk) {
        return true;
      }
      idx = haystack.indexOf(needle, idx + 1);
    }
    return false;
  }

  private static CharacterProfile apply(CharacterProfile base, Layer layer) {
    if (layer == null) {
      return base;
    }
    return new CharacterProfile(
        layer.name() != null ? layer.name() : base.name(),
        layer.accent() != null ? layer.accent() : base.accent(),
        layer.style() != null ? layer.style() : base.style(),
        layer.pace() != null ? layer.pace() : base.pace());
  }

  /**
   * Parses an object of {@code key -> sparse layer} (e.g. byRace, byEthnicity), keyed lower-case.
   */
  private static Map<String, Layer> parseLayerMap(JsonObject obj) {
    if (obj == null) {
      return Collections.emptyMap();
    }
    Map<String, Layer> map = new HashMap<>();
    for (String key : obj.keySet()) {
      if (isComment(key) || !obj.get(key).isJsonObject()) {
        continue;
      }
      map.put(key.toLowerCase(Locale.ROOT), parseLayer(obj.getAsJsonObject(key)));
    }
    return Collections.unmodifiableMap(map);
  }

  /** Parses a sparse layer; absent fields stay {@code null} so they inherit. */
  private static Layer parseLayer(JsonObject obj) {
    if (obj == null) {
      return null;
    }
    return new Layer(
        optString(obj, "name"),
        optString(obj, "accent"),
        optString(obj, "style"),
        optString(obj, "pace"));
  }

  /**
   * Parses a layer that must be complete (all four fields); returns {@code null} if any is absent.
   */
  private static CharacterProfile parseComplete(JsonObject obj) {
    Layer layer = parseLayer(obj);
    if (layer == null
        || layer.name() == null
        || layer.accent() == null
        || layer.style() == null
        || layer.pace() == null) {
      return null;
    }
    return new CharacterProfile(layer.name(), layer.accent(), layer.style(), layer.pace());
  }

  private static JsonObject optObject(JsonObject parent, String key) {
    return parent.has(key) && parent.get(key).isJsonObject() ? parent.getAsJsonObject(key) : null;
  }

  private static String optString(JsonObject obj, String key) {
    if (!obj.has(key) || obj.get(key).isJsonNull()) {
      return null;
    }
    String value = obj.get(key).getAsString();
    return value.isEmpty() ? null : value;
  }

  private static boolean isComment(String key) {
    return key.startsWith("_");
  }

  private static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }
}
