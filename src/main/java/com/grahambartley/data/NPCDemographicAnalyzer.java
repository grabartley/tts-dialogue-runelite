package com.grahambartley.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;

/**
 * Resolves an NPC's race/gender from a static, precomputed lookup table baked into the plugin as a
 * bundled resource ({@code /npc-voices.json}).
 *
 * <p>At runtime this is a single in-memory map lookup keyed by NPC id: no network requests, no
 * large downloads, no model-id guessing. The table is produced offline by {@code
 * tools/generate_npc_voices.py}; see the README for how to regenerate and expand it.
 *
 * <p>Ids missing from the table resolve to race {@code Unknown} so the configured voice fallback
 * applies (a gender-appropriate human voice, or the single default voice when fallbacks are off).
 * As a last-resort gender hint for those entries only, an explicit female title or word in the NPC
 * name picks the female fallback; everything else defaults to male. This is the lone runtime name
 * check and never affects race or table-backed NPCs.
 */
@Slf4j
public class NPCDemographicAnalyzer {

  private static final String TABLE_RESOURCE = "/npc-voices.json";
  private static final String DEFAULT_RACE = "Unknown";
  private static final String DEFAULT_GENDER = "Male";

  /**
   * Explicit female titles/words used only to pick the gender of the fallback voice for NPCs
   * missing from the table. Word-aware so it can't match inside larger words. Mirrors the offline
   * generator's female keywords; keep the two in sync.
   */
  private static final Pattern FEMALE_NAME_HINT =
      Pattern.compile(
          "\\b(woman|women|girl|lady|queen|princess|duchess|countess|baroness|empress|witch"
              + "|sorceress|priestess|huntress|banshee|hag|crone|mother|sister|nun|maiden|barmaid"
              + "|waitress|seamstress|goddess|mistress|madam|damsel|wife|maid)\\b",
          Pattern.CASE_INSENSITIVE);

  /** Immutable npcId -> {race, gender} table loaded once from the bundled resource. */
  private Map<Integer, NPCAttributes> voiceTable = Collections.emptyMap();

  /** Loads the static lookup table from the bundled resource. */
  public void initialize() {
    voiceTable = loadVoiceTable();
    log.info("NPC voice table loaded with {} entries from {}", voiceTable.size(), TABLE_RESOURCE);
  }

  /**
   * Resolves race/gender for an NPC by a single lookup in the static table, keyed by NPC id.
   * Returns a deterministic default for ids missing from the table; returns {@code null} only when
   * the NPC (or its composition) is itself null.
   */
  public NPCAttributes analyzeNPC(NPC npc) {
    if (npc == null) {
      return null;
    }
    NPCComposition composition = npc.getComposition();
    if (composition == null) {
      return null;
    }

    return lookup(composition.getId(), composition.getName());
  }

  /**
   * Resolves race/gender for an NPC id by a single map lookup. Ids missing from the table get a
   * deterministic default (race {@code Unknown}, gender from the female-name hint) so the
   * configured fallback voice always applies.
   */
  public NPCAttributes lookup(int npcId, String npcName) {
    NPCAttributes attributes = voiceTable.get(npcId);
    if (attributes != null) {
      return attributes;
    }
    return defaultAttributes(npcId, npcName);
  }

  /** Number of entries in the loaded table (for logging and tests). */
  public int getTableSize() {
    return voiceTable.size();
  }

  private NPCAttributes defaultAttributes(int npcId, String npcName) {
    String gender =
        npcName != null && FEMALE_NAME_HINT.matcher(npcName).find() ? "Female" : DEFAULT_GENDER;
    NPCAttributes defaultData = new NPCAttributes(DEFAULT_RACE, gender, "Default", 0.0);
    defaultData.setNpcId(npcId);
    defaultData.setName(npcName);
    defaultData.setNotes("No table entry - using default");
    return defaultData;
  }

  private Map<Integer, NPCAttributes> loadVoiceTable() {
    Map<Integer, NPCAttributes> table = new HashMap<>();
    try (InputStream stream = getClass().getResourceAsStream(TABLE_RESOURCE)) {
      if (stream == null) {
        log.warn(
            "NPC voice table {} not found - every NPC will use the default voice", TABLE_RESOURCE);
        return Collections.emptyMap();
      }

      // Note: the bundled Gson predates the static JsonParser.parseReader API, so the instance
      // method is used here.
      JsonObject root =
          new JsonParser()
              .parse(new InputStreamReader(stream, StandardCharsets.UTF_8))
              .getAsJsonObject();
      if (!root.has("npcs")) {
        log.warn("NPC voice table {} has no 'npcs' object - using default voice", TABLE_RESOURCE);
        return Collections.emptyMap();
      }

      JsonObject npcs = root.getAsJsonObject("npcs");
      for (String npcIdStr : npcs.keySet()) {
        try {
          int npcId = Integer.parseInt(npcIdStr);
          JsonObject entry = npcs.getAsJsonObject(npcIdStr);

          NPCAttributes attributes =
              new NPCAttributes(
                  entry.get("race").getAsString(),
                  entry.get("gender").getAsString(),
                  "StaticTable",
                  1.0);
          attributes.setNpcId(npcId);
          table.put(npcId, attributes);
        } catch (RuntimeException e) {
          log.warn("Skipping malformed NPC voice entry {}: {}", npcIdStr, e.getMessage());
        }
      }
    } catch (Exception e) {
      log.error("Failed to load NPC voice table {}: {}", TABLE_RESOURCE, e.getMessage());
      return Collections.emptyMap();
    }
    return Collections.unmodifiableMap(table);
  }
}
