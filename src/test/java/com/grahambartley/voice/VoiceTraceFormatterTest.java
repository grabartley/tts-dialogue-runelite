package com.grahambartley.voice;

import static org.junit.Assert.assertTrue;

import com.grahambartley.voice.VoiceManager.NPCGender;
import com.grahambartley.voice.VoiceManager.NPCRace;
import org.junit.Test;

/** The debug voice-resolution trace strings. */
public class VoiceTraceFormatterTest {

  @Test
  public void buildNpcTraceShowsWorldHitSourceAndChosenSpeaker() {
    String trace =
        VoiceTraceFormatter.buildNpcTrace(
            "Goblin", 101, NPCRace.GOBLIN, NPCGender.MALE, "table-hit", 24);
    assertTrue(trace, trace.contains("npc='Goblin'"));
    assertTrue(trace, trace.contains("world=HIT(id=101)"));
    assertTrue(trace, trace.contains("race=GOBLIN"));
    assertTrue(trace, trace.contains("source=table-hit"));
    assertTrue(trace, trace.contains("speakerId=24"));
    assertTrue(trace, trace.contains("bm_daniel"));
  }

  @Test
  public void buildNpcTraceShowsWorldMissForUntabledNpc() {
    String trace =
        VoiceTraceFormatter.buildNpcTrace(
            "Hans", null, NPCRace.UNKNOWN, NPCGender.UNKNOWN, "not-in-world", 26);
    assertTrue(trace, trace.contains("world=MISS"));
    assertTrue(trace, trace.contains("race=UNKNOWN"));
    assertTrue(trace, trace.contains("bm_george"));
  }

  @Test
  public void buildResolvedLineGivesTheWholeDecisionInOneRecord() {
    String line =
        VoiceTraceFormatter.buildResolvedLine(
            "cloud-openrouter",
            false,
            "Hans",
            "HAPPY",
            NPCRace.HUMAN,
            NPCGender.MALE,
            26,
            "Hans",
            "British");
    assertTrue(line, line.startsWith("[TTS line]"));
    assertTrue(line, line.contains("backend=cloud-openrouter"));
    assertTrue(line, line.contains("kind=npc"));
    assertTrue(line, line.contains("name='Hans'"));
    assertTrue(line, line.contains("emotion=HAPPY"));
    assertTrue(line, line.contains("race=HUMAN"));
    assertTrue(line, line.contains("gender=MALE"));
    assertTrue(line, line.contains("speaker=bm_george(26)"));
    assertTrue(line, line.contains("profile='Hans'"));
    assertTrue(line, line.contains("accent='British'"));
  }

  @Test
  public void buildResolvedLineCollapsesAbsentSpeakerAndProfileToDash() {
    String line =
        VoiceTraceFormatter.buildResolvedLine(
            "local-kokoro", true, null, "NEUTRAL", NPCRace.HUMAN, NPCGender.FEMALE, -1, null, null);
    assertTrue(line, line.contains("kind=player"));
    assertTrue(line, line.contains("name=-"));
    assertTrue(line, line.contains("speaker=-"));
    assertTrue(line, line.contains("profile=-"));
    assertTrue(line, line.contains("accent=-"));
  }

  @Test
  public void buildPlayerTraceNamesTheSpeaker() {
    String trace = VoiceTraceFormatter.buildPlayerTrace(NPCGender.FEMALE);
    assertTrue(trace, trace.contains("player ->"));
    assertTrue(trace, trace.contains("gender=FEMALE"));
    assertTrue(
        trace, trace.contains("speakerId=" + KokoroSpeakerPool.playerSpeaker(NPCGender.FEMALE)));
  }
}
