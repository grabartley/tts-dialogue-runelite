package com.grahambartley.data;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import lombok.extern.slf4j.Slf4j;

/**
 * Coordinates the runtime "learn a new NPC" fallback: when an NPC is missing from the bundled table
 * and the feature is enabled, it looks the NPC up on the wiki ({@link WikiNpcClient}) on a
 * background thread and records the result in the {@link LearnedNpcStore}, so the NPC voices
 * correctly from the next line on. The triggering line itself still uses the default voice, because
 * resolution happens on the game thread and must never block on the network.
 *
 * <p>Each unknown id is attempted at most once per session (a wiki miss is not retried in a loop),
 * and an already-learned id is never re-fetched.
 */
@Slf4j
public final class NpcLearningService {

  private final WikiNpcClient client;
  private final LearnedNpcStore store;
  private final Executor executor;
  private final BooleanSupplier enabled;
  private final Set<Integer> attempted = ConcurrentHashMap.newKeySet();

  public NpcLearningService(
      WikiNpcClient client, LearnedNpcStore store, Executor executor, BooleanSupplier enabled) {
    this.client = client;
    this.store = store;
    this.executor = executor;
    this.enabled = enabled;
  }

  /**
   * Schedules a one-off background wiki lookup for an unknown NPC, if the feature is enabled and
   * this id has neither been learned nor already attempted this session. Returns immediately; safe
   * to call on the game thread.
   */
  public void considerLearning(int npcId, String npcName) {
    if (!enabled.getAsBoolean() || npcName == null || npcName.isEmpty()) {
      return;
    }
    if (store.get(npcId) != null || !attempted.add(npcId)) {
      return;
    }
    executor.execute(
        () -> {
          NPCAttributes attributes = client.lookup(npcName);
          if (attributes == null) {
            log.debug("[TTS learn] wiki had no usable entry for '{}' (id {})", npcName, npcId);
            return;
          }
          store.learn(
              npcId, attributes.getRace(), attributes.getGender(), attributes.getEthnicity());
          log.info(
              "[TTS learn] learned '{}' (id {}) from wiki -> race={} gender={} ethnicity={}",
              npcName,
              npcId,
              attributes.getRace(),
              attributes.getGender(),
              attributes.getEthnicity() == null ? "-" : attributes.getEthnicity());
        });
  }
}
