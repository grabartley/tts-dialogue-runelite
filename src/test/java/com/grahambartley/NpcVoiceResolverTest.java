package com.grahambartley;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grahambartley.VoiceManager.NPCGender;
import com.grahambartley.VoiceManager.NPCRace;
import com.grahambartley.data.NPCAttributes;
import com.grahambartley.data.NPCDemographicAnalyzer;
import com.grahambartley.data.NpcLearningService;
import com.grahambartley.synthesis.VoiceSpec;
import net.runelite.api.NPC;
import org.junit.Test;

/** NPC name to {@link VoiceSpec} resolution, including detection-failure fallbacks and learning. */
public class NpcVoiceResolverTest {

  private final TTSDialogueConfig config = mock(TTSDialogueConfig.class);
  private final NPCDemographicAnalyzer analyzer = mock(NPCDemographicAnalyzer.class);
  private final NpcFinder finder = mock(NpcFinder.class);
  private final NpcVoiceResolver resolver = new NpcVoiceResolver(config, analyzer, finder);

  @Test
  public void blankNameResolvesToDefaultHumanMale() {
    VoiceSpec spec = resolver.resolve("");
    assertDefaultHumanMale(spec);
  }

  @Test
  public void npcNotInWorldResolvesToDefaultHumanMale() {
    when(finder.findByName("Hans")).thenReturn(null);
    assertDefaultHumanMale(resolver.resolve("Hans"));
  }

  @Test
  public void analysisFailureResolvesToDefaultHumanMale() {
    NPC npc = mock(NPC.class);
    when(npc.getId()).thenReturn(5);
    when(finder.findByName("Hans")).thenReturn(npc);
    when(analyzer.analyzeNPC(npc)).thenReturn(null);
    assertDefaultHumanMale(resolver.resolve("Hans"));
  }

  @Test
  public void detectedNpcCarriesItsRaceAndGender() {
    NPC npc = mock(NPC.class);
    when(npc.getId()).thenReturn(101);
    when(finder.findByName("Goblin")).thenReturn(npc);
    NPCAttributes attrs = attributes("Goblin", "Male", "StaticTable");
    when(analyzer.analyzeNPC(npc)).thenReturn(attrs);
    NpcLearningService learning = mock(NpcLearningService.class);
    resolver.setLearningService(learning);

    VoiceSpec spec = resolver.resolve("Goblin");

    assertEquals(NPCRace.GOBLIN, spec.race());
    assertEquals(NPCGender.MALE, spec.gender());
    assertFalse(spec.player());
    verify(learning, never()).considerLearning(101, "Goblin");
  }

  @Test
  public void unknownRaceVoicesAsHumanAndTriggersLearning() {
    NPC npc = mock(NPC.class);
    when(npc.getId()).thenReturn(202);
    when(finder.findByName("Penguin")).thenReturn(npc);
    NPCAttributes attrs = attributes("Penguin", "Female", "learned");
    when(analyzer.analyzeNPC(npc)).thenReturn(attrs);
    NpcLearningService learning = mock(NpcLearningService.class);
    resolver.setLearningService(learning);

    VoiceSpec spec = resolver.resolve("Penguin");

    assertEquals("an unrecognised race voices as human", NPCRace.HUMAN, spec.race());
    assertEquals(NPCGender.FEMALE, spec.gender());
    verify(learning).considerLearning(202, "Penguin");
  }

  private void assertDefaultHumanMale(VoiceSpec spec) {
    assertEquals(NPCRace.HUMAN, spec.race());
    assertEquals(NPCGender.MALE, spec.gender());
    assertFalse("default voice is not a player spec", spec.player());
    assertTrue(
        "default voice still gets a per-NPC British speaker", spec.hasExplicitKokoroSpeakerId());
  }

  private static NPCAttributes attributes(String race, String gender, String source) {
    NPCAttributes attributes = mock(NPCAttributes.class);
    when(attributes.getRace()).thenReturn(race);
    when(attributes.getGender()).thenReturn(gender);
    when(attributes.getSource()).thenReturn(source);
    return attributes;
  }
}
