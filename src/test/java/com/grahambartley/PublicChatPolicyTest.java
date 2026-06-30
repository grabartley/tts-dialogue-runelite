package com.grahambartley;

import static org.junit.Assert.assertEquals;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The local-player name filter for the public-chat feature (#138): keeps only the player's own
 * public chat. Pure and null-safe, so it verifies without a live client.
 */
@RunWith(JUnitParamsRunner.class)
public class PublicChatPolicyTest {

  private Object[] selfPublicChatCases() {
    return new Object[] {
      // plain identical names match
      new Object[] {"Zezima", "Zezima", true},
      // a clan/friend rank <img> icon prefix on the chat name is stripped before comparing
      new Object[] {"<img=2>Zezima", "Zezima", true},
      // a non-breaking space in the chat name is normalised to a regular space
      new Object[] {"Big Bird", "Big Bird", true},
      // rank icon and nbsp together still match
      new Object[] {"<img=5>Big Bird", "Big Bird", true},
      // a different player is dropped
      new Object[] {"Woox", "Zezima", false},
      // a different player behind a rank icon is still dropped
      new Object[] {"<img=2>Woox", "Zezima", false},
      // a null chat name never matches
      new Object[] {null, "Zezima", false},
      // a null local name (not logged in) never matches
      new Object[] {"Zezima", null, false},
    };
  }

  @Test
  @Parameters(method = "selfPublicChatCases")
  public void selfFilterKeepsOnlyTheLocalPlayerIgnoringRankIconsAndNbsp(
      String eventName, String localName, boolean expected) {
    assertEquals(expected, PublicChatPolicy.isSelfPublicChat(eventName, localName));
  }
}
