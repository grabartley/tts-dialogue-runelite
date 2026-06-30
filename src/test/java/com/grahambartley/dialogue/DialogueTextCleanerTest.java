package com.grahambartley.dialogue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.grahambartley.synthesis.ProfanityFilter;
import org.junit.Test;

/**
 * The single spoken-text chokepoint (#149): {@link DialogueTextCleaner#clean} strips markup, then
 * masks profanity unconditionally. Every voiced source (NPC dialogue, player options, public chat)
 * funnels through it, so this one seam covers them all with no toggle to bypass.
 */
public class DialogueTextCleanerTest {

  private final DialogueTextCleaner cleaner = new DialogueTextCleaner(new ProfanityFilter());

  @Test
  public void masksProfanityAfterStrippingMarkup() {
    assertEquals(
        "rank icons and tags are stripped, then the slur is bleeped",
        "You ****!",
        cleaner.clean("<img=2>You <col=ff0000>twat</col>!"));
  }

  @Test
  public void attackerControlledPublicChatIsMaskedVerbatimPath() {
    String masked = cleaner.clean("get rekt you sh1t");
    assertFalse("the leetspeak evasion does not survive into synthesis", masked.contains("sh1t"));
    assertEquals("get rekt you ****", masked);
  }

  @Test
  public void cleanDialogueIsUntouched() {
    assertEquals(
        "Greetings, adventurer.", cleaner.clean("<col=00ff00>Greetings, adventurer.</col>"));
  }
}
