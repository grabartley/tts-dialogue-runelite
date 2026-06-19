package com.grahambartley.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;

/**
 * Resolves an NPC's race/gender from a static, precomputed lookup table baked into the plugin as a
 * bundled resource ({@code /npc-voices.json}).
 *
 * <p>At runtime this is a single in-memory map lookup keyed by NPC id: no network requests, no
 * large downloads, no name scraping or model-id guessing. The table is produced offline by {@code
 * tools/generate_npc_voices.py}; see the README for how to regenerate and expand it.
 *
 * <p>Ids missing from the table resolve to a deterministic default (Human/Male) so a voice is
 * always chosen without error.
 */
@Slf4j
public class NPCDemographicAnalyzer {

  private static final String TABLE_RESOURCE = "/npc-voices.json";
  private static final String DEFAULT_RACE = "Human";
  private static final String DEFAULT_GENDER = "Male";

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
   * deterministic default (Human/Male) so a voice is always chosen.
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
    NPCAttributes defaultData = new NPCAttributes(DEFAULT_RACE, DEFAULT_GENDER, "Default", 0.0);
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
