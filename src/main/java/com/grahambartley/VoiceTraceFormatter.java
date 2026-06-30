package com.grahambartley;

import com.grahambartley.VoiceManager.KokoroVoice;
import com.grahambartley.VoiceManager.NPCGender;
import com.grahambartley.VoiceManager.NPCRace;

/**
 * Formats the debug voice-resolution trace strings. Pure string building, so the whole resolution
 * path (world hit/id, table hit/miss, detected race/gender + source) and the chosen British speaker
 * id/name are verifiable without a live client or logger.
 */
final class VoiceTraceFormatter {

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
