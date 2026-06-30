package com.grahambartley.synthesis;

import com.grahambartley.TTSDialogueConfig;
import java.util.Set;

/**
 * Pure decision for the runtime backend-switch warm-up trigger (#75): whether a {@link
 * net.runelite.client.events.ConfigChanged} should re-run the active backend's off-thread warm-up.
 * Factored out of the plugin so it is testable without RuneLite injection.
 */
public final class BackendWarmUpPolicy {

  /**
   * Config keys that change which backend is selected or whether it can become available. {@code
   * voiceBackend} switches the selection; {@code openRouterApiKey} lets a previously-unavailable
   * Cloud selection become available once a key is entered.
   */
  private static final Set<String> WARM_TRIGGER_KEYS = Set.of("voiceBackend", "openRouterApiKey");

  private BackendWarmUpPolicy() {}

  /**
   * Returns {@code true} only when a changed config entry belongs to this plugin's group and its
   * key affects backend selection or availability. Never throws; tolerates {@code null} group/key.
   */
  public static boolean affectsBackendWarmUp(String group, String key) {
    // key != null first: WARM_TRIGGER_KEYS is an immutable Set.of(...), whose contains(null)
    // throws.
    return TTSDialogueConfig.GROUP.equals(group) && key != null && WARM_TRIGGER_KEYS.contains(key);
  }
}
