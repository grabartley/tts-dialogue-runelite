package com.grahambartley.voice;

import net.runelite.api.Client;
import net.runelite.api.NPC;

/**
 * Finds an NPC entity by name in the current game world. Matching is tolerant of presentation
 * differences between the dialogue name widget (which can carry {@code <col=...>} markup,
 * non-breaking spaces, and casing) and the raw composition name: both sides are normalised via
 * {@link NameNormalizer} and compared case-insensitively, so cosmetic markup never forces a false
 * miss and the default voice.
 */
final class NpcFinder {

  private final Client client;

  NpcFinder(Client client) {
    this.client = client;
  }

  NPC findByName(String targetName) {
    if (client == null || client.getNpcs() == null) {
      return null;
    }

    String wanted = NameNormalizer.normalize(targetName);
    if (wanted.isEmpty()) {
      return null;
    }

    return client.getNpcs().stream()
        .filter(npc -> npc != null && npc.getName() != null)
        .filter(npc -> NameNormalizer.normalize(npc.getName()).equalsIgnoreCase(wanted))
        .findFirst()
        .orElse(null);
  }
}
