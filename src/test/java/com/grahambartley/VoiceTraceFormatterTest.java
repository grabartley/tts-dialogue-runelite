package com.grahambartley;

import static org.junit.Assert.assertTrue;

import com.grahambartley.VoiceManager.NPCGender;
import com.grahambartley.VoiceManager.NPCRace;
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
  public void buildPlayerTraceNamesTheSpeaker() {
    String trace = VoiceTraceFormatter.buildPlayerTrace(NPCGender.FEMALE);
    assertTrue(trace, trace.contains("player ->"));
    assertTrue(trace, trace.contains("gender=FEMALE"));
    assertTrue(
        trace, trace.contains("speakerId=" + KokoroSpeakerPool.playerSpeaker(NPCGender.FEMALE)));
  }
}
