package com.grahambartley;

import static org.junit.Assert.assertEquals;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Pure decision logic for the runtime backend-switch warm-up trigger (#75): only the plugin group
 * plus a backend-affecting key should warm.
 */
@RunWith(JUnitParamsRunner.class)
public class BackendWarmUpPolicyTest {

  private Object[] warmUpCases() {
    return new Object[] {
      // Plugin group with a backend-affecting key warms.
      new Object[] {"ttsDialogue", "voiceBackend", true},
      new Object[] {"ttsDialogue", "openRouterApiKey", true},
      // Right group, key that does not affect backend selection/availability.
      new Object[] {"ttsDialogue", "volume", false},
      // A backend key but a different plugin's config group.
      new Object[] {"otherPlugin", "voiceBackend", false},
      // Defensive: nulls never throw and never warm.
      new Object[] {null, "voiceBackend", false},
      new Object[] {"ttsDialogue", null, false},
    };
  }

  @Test
  @Parameters(method = "warmUpCases")
  public void affectsBackendWarmUp(String group, String key, boolean expected) {
    assertEquals(expected, BackendWarmUpPolicy.affectsBackendWarmUp(group, key));
  }
}
