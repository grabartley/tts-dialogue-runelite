package com.grahambartley;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grahambartley.TTSDialogueConfig.VoiceBackend;
import com.grahambartley.synthesis.OpenRouterTtsBackend;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The plugin's user-facing chat notices: the once-ever first-run onboarding guide and the
 * once-per-session missing-cloud-key warning, plus the pure decisions behind them.
 */
@RunWith(JUnitParamsRunner.class)
public class ChatNoticeManagerTest {

  private final Client client = mock(Client.class);
  private final ConfigManager configManager = mock(ConfigManager.class);
  private final ClientThread clientThread = mock(ClientThread.class);
  private final TTSDialogueConfig config = mock(TTSDialogueConfig.class);
  private final ChatNoticeManager manager =
      new ChatNoticeManager(client, configManager, clientThread, config);

  private Object[] onboardingCases() {
    return new Object[] {
      // fresh install (flag never set) shows the guide
      new Object[] {null, true},
      // flag explicitly false shows the guide
      new Object[] {false, true},
      // flag true suppresses the guide
      new Object[] {true, false},
    };
  }

  @Test
  @Parameters(method = "onboardingCases")
  public void shouldShowOnboardingOnlyUntilSeen(Boolean seen, boolean expected) {
    assertEquals(expected, ChatNoticeManager.shouldShowOnboarding(seen));
  }

  private Object[] missingCloudKeyCases() {
    return new Object[] {
      // Cloud with a blank key warns
      new Object[] {VoiceBackend.CLOUD, false, true},
      // Cloud with a key set stays quiet
      new Object[] {VoiceBackend.CLOUD, true, false},
      // Local needs no key, so it never warns
      new Object[] {VoiceBackend.LOCAL, false, false},
      // Local with a key set still never warns
      new Object[] {VoiceBackend.LOCAL, true, false},
    };
  }

  @Test
  @Parameters(method = "missingCloudKeyCases")
  public void shouldWarnMissingCloudKeyOnlyForCloudWithNoKey(
      VoiceBackend backend, boolean keySet, boolean expected) {
    assertEquals(expected, ChatNoticeManager.shouldWarnMissingCloudKey(backend, keySet));
  }

  @Test
  public void onboardingPostsOnceOnFreshInstallAndPersistsTheFlag() {
    when(configManager.getConfiguration("ttsDialogue", "onboardingSeen", Boolean.class))
        .thenReturn(null);

    manager.maybeShowOnboarding();
    manager.maybeShowOnboarding();

    verify(client, times(1))
        .addChatMessage(
            eq(ChatMessageType.GAMEMESSAGE), eq(""), contains("Voiced Dialogue is on"), isNull());
    verify(configManager, times(1)).setConfiguration("ttsDialogue", "onboardingSeen", true);
  }

  @Test
  public void onboardingStaysQuietOnceSeen() {
    when(configManager.getConfiguration("ttsDialogue", "onboardingSeen", Boolean.class))
        .thenReturn(true);

    manager.maybeShowOnboarding();

    verify(client, never())
        .addChatMessage(eq(ChatMessageType.GAMEMESSAGE), eq(""), contains(""), isNull());
    verify(configManager, never()).setConfiguration("ttsDialogue", "onboardingSeen", true);
  }

  @Test
  public void missingKeyWarningPostsOnceForCloudWithNoKey() {
    when(config.voiceBackend()).thenReturn(VoiceBackend.CLOUD);

    manager.maybeWarnMissingCloudKey(false);
    manager.maybeWarnMissingCloudKey(false);

    verify(client, times(1))
        .addChatMessage(
            eq(ChatMessageType.GAMEMESSAGE),
            eq(""),
            contains(OpenRouterTtsBackend.NO_KEY_NOTICE),
            isNull());
  }

  @Test
  public void missingKeyWarningStaysQuietWhenKeyAvailable() {
    when(config.voiceBackend()).thenReturn(VoiceBackend.CLOUD);

    manager.maybeWarnMissingCloudKey(true);

    verify(client, never())
        .addChatMessage(eq(ChatMessageType.GAMEMESSAGE), eq(""), contains(""), isNull());
  }
}
