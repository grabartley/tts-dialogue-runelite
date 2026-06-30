package com.grahambartley.voice;

import com.grahambartley.voice.VoiceManager.KokoroVoice;
import com.grahambartley.voice.VoiceManager.NPCGender;
import com.grahambartley.voice.VoiceManager.NPCRace;

/**
 * Formats the debug voice-resolution trace strings. Pure string building, so the whole resolution
 * path (world hit/id, table hit/miss, detected race/gender + source) and the chosen British speaker
 * id/name are verifiable without a live client or logger.
 */
public final class VoiceTraceFormatter {

  private VoiceTraceFormatter() {}

  static String buildNpcTrace(
      String npcName,
      Integer npcId,
      NPCRace race,
      NPCGender gender,
      String source,
      int chosenSpeakerId) {
    return String.format(
        "[TTS voice] npc='%s' world=%s race=%s gender=%s source=%s -> speaker=%s(speakerId=%d)",
        npcName,
        npcId == null ? "MISS" : "HIT(id=" + npcId + ")",
        race == null ? "UNKNOWN" : race,
        gender,
        source,
        KokoroVoice.nameFor(chosenSpeakerId),
        chosenSpeakerId);
  }

  /**
   * One consolidated record of the whole resolved decision for a voiced line, so a single grep over
   * {@code [TTS line]} gives the backend, emotion, and the full voice metadata (race, gender,
   * speaker, profile, accent) actually used for synthesis. The detected npc id and ethnicity stay
   * on the adjacent {@code [TTS profile]}/{@code [TTS voice]} traces, which this complements rather
   * than replaces. A null profile (profiles off) renders {@code -}.
   */
  public static String buildResolvedLine(
      String backendId,
      boolean player,
      String npcName,
      String emotion,
      NPCRace race,
      NPCGender gender,
      int speakerId,
      String profileName,
      String accent) {
    return String.format(
        "[TTS line] backend=%s kind=%s name=%s emotion=%s race=%s gender=%s speaker=%s profile=%s"
            + " accent=%s",
        backendId,
        player ? "player" : "npc",
        player ? "-" : "'" + npcName + "'",
        emotion,
        race,
        gender,
        speakerId < 0 ? "-" : KokoroVoice.nameFor(speakerId) + "(" + speakerId + ")",
        profileName == null ? "-" : "'" + profileName + "'",
        accent == null ? "-" : "'" + accent + "'");
  }

  static String buildPlayerTrace(NPCGender gender) {
    int speakerId = KokoroSpeakerPool.playerSpeaker(gender);
    return String.format(
        "[TTS voice] player -> speaker=%s(speakerId=%d) gender=%s",
        KokoroVoice.nameFor(speakerId), speakerId, gender);
  }

  /** The Kokoro voice name for a speaker id, or a bare {@code "id=<n>"} when outside the bank. */
  static String kokoroVoiceName(int speakerId) {
    return KokoroVoice.nameFor(speakerId);
  }
}
