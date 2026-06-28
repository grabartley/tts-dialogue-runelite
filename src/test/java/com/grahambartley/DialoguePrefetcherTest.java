package com.grahambartley;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.grahambartley.VoiceManager.NPCGender;
import com.grahambartley.VoiceManager.NPCRace;
import com.grahambartley.synthesis.Emotion;
import com.grahambartley.synthesis.SynthesisRequest;
import com.grahambartley.synthesis.VoiceSpec;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** Session dedup, the per-session cap, the enable gate, and reset/cancel behaviour. */
public class DialoguePrefetcherTest {

  private final List<SynthesisRequest> warmed = new ArrayList<>();
  private int cancels;
  private boolean enabled = true;

  private DialoguePrefetcher prefetcher() {
    return new DialoguePrefetcher(warmed::add, () -> cancels++, () -> enabled);
  }

  private static SynthesisRequest req(String text) {
    return new SynthesisRequest(
        text, VoiceSpec.npc(NPCRace.HUMAN, NPCGender.MALE), Emotion.NEUTRAL);
  }

  private static List<SynthesisRequest> options(String... texts) {
    List<SynthesisRequest> list = new ArrayList<>();
    for (String text : texts) {
      list.add(req(text));
    }
    return list;
  }

  @Test
  public void offersEachReachableLineOnce() {
    DialoguePrefetcher prefetcher = prefetcher();

    prefetcher.offer(options("Yes.", "No.", "Maybe."));

    assertEquals("every distinct option is warmed once", 3, warmed.size());
  }

  @Test
  public void reOfferingTheSameOptionsEachTickDoesNotReWarm() {
    DialoguePrefetcher prefetcher = prefetcher();

    prefetcher.offer(options("Yes.", "No."));
    prefetcher.offer(options("Yes.", "No."));
    prefetcher.offer(options("Yes.", "No."));

    assertEquals("the same options are only warmed once per session", 2, warmed.size());
  }

  @Test
  public void capsPrefetchesPerSession() {
    DialoguePrefetcher prefetcher = prefetcher();

    List<SynthesisRequest> many = new ArrayList<>();
    for (int i = 0; i < DialoguePrefetcher.MAX_PER_SESSION + 5; i++) {
      many.add(req("Option " + i));
    }
    prefetcher.offer(many);

    assertEquals(
        "a session never warms more than the cap",
        DialoguePrefetcher.MAX_PER_SESSION,
        warmed.size());
  }

  @Test
  public void blankAndNullOptionsAreSkippedWithoutConsumingTheCap() {
    DialoguePrefetcher prefetcher = prefetcher();

    List<SynthesisRequest> mixed = new ArrayList<>();
    mixed.add(req(""));
    mixed.add(null);
    mixed.add(req("Real line."));
    prefetcher.offer(mixed);

    assertEquals("only the real line is warmed", 1, warmed.size());
    assertEquals("Real line.", warmed.get(0).text());
  }

  @Test
  public void disabledGateMakesOfferANoOp() {
    enabled = false;
    DialoguePrefetcher prefetcher = prefetcher();

    prefetcher.offer(options("Yes.", "No."));

    assertEquals("nothing is warmed when prefetch is off", 0, warmed.size());
  }

  @Test
  public void resetCancelsAndStartsAFreshSession() {
    DialoguePrefetcher prefetcher = prefetcher();

    prefetcher.offer(options("Yes.", "No."));
    prefetcher.reset();
    // After reset the same options are a new session, so they warm again.
    prefetcher.offer(options("Yes.", "No."));

    assertEquals("reset clears dedup so a new conversation re-warms", 4, warmed.size());
    assertTrue("reset cancels still-queued prefetches", cancels >= 1);
  }
}
