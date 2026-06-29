package com.grahambartley;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.grahambartley.synthesis.ProfanityFilter;
import org.junit.Test;

/**
 * The single spoken-text chokepoint (#149): {@link TTSDialoguePlugin#cleanDialogueText} strips
 * markup, then masks profanity unconditionally. Every voiced source (NPC dialogue, player options,
 * public chat) funnels through it, so this one seam covers them all with no toggle to bypass.
 */
public class TTSDialoguePluginProfanityTest {

  private final ProfanityFilter filter = new ProfanityFilter();

  @Test
  public void masksProfanityAfterStrippingMarkup() {
    assertEquals(
        "rank icons and tags are stripped, then the slur is bleeped",
        "You ****!",
        TTSDialoguePlugin.cleanDialogueText("<img=2>You <col=ff0000>twat</col>!", filter));
  }

  @Test
  public void attackerControlledPublicChatIsMaskedVerbatimPath() {
    String masked = TTSDialoguePlugin.cleanDialogueText("get rekt you sh1t", filter);
    assertFalse("the leetspeak evasion does not survive into synthesis", masked.contains("sh1t"));
    assertEquals("get rekt you ****", masked);
  }

  @Test
  public void cleanDialogueIsUntouched() {
    assertEquals(
        "Greetings, adventurer.",
        TTSDialoguePlugin.cleanDialogueText("<col=00ff00>Greetings, adventurer.</col>", filter));
  }
}
