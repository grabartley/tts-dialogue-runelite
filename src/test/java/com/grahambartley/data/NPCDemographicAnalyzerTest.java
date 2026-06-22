package com.grahambartley.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/**
 * Verifies the static NPC voice table lookup: known ids resolve to the baked-in race/gender,
 * unknown ids fall back deterministically, and no live data source is consulted. The lookup is
 * exercised by id directly so the heavy {@code net.runelite.api.NPC} interface needn't be mocked.
 */
public class NPCDemographicAnalyzerTest {

  private NPCDemographicAnalyzer analyzer;

  @Before
  public void setUp() {
    analyzer = new NPCDemographicAnalyzer();
    analyzer.initialize();
  }

  @Test
  public void bundledTableLoadsEntries() {
    // The generated resource ships with a substantial set of entries, far more than the old
    // 24-entry hand list, so most distinctive NPCs no longer rely on the default.
    assertTrue("expected the bundled table to load many entries", analyzer.getTableSize() > 500);
  }

  @Test
  public void knownNpcsResolveToCorrectRaceAndGender() {
    // Real RuneLite/OSRS cache ids (the same ids the live client reports and the
    // generator sources from), spanning each distinctive race bucket.
    assertAttributes(385, "Human", "Male"); // Man
    assertAttributes(1119, "Human", "Female"); // Woman
    assertAttributes(655, "Goblin", "Male"); // Goblin
    assertAttributes(70, "Undead", "Male"); // Skeleton
    assertAttributes(85, "Undead", "Male"); // Ghost
    assertAttributes(4733, "Dwarf", "Male"); // Thurgo
    assertAttributes(142, "Demon", "Male"); // Demon
    assertAttributes(7746, "Wizard", "Male"); // Wizard Mizgog
  }

  @Test
  public void dialogueNpcsResolveToCorrectGenderAndRace() {
    // High-traffic peaceful dialogue NPCs (the acceptance-criteria sample) must
    // now be present with the correct gender so male and female townsfolk get
    // distinct voices on the Kokoro backend, instead of collapsing to the
    // human-male default. Ids are real cache ids verified against the osrs data.
    assertAttributes(3105, "Human", "Male"); // Hans
    assertAttributes(306, "Human", "Male"); // Lumbridge Guide
    assertAttributes(225, "Human", "Male"); // Cook
    assertAttributes(2812, "Human", "Male"); // Father Aereck
    assertAttributes(5037, "Human", "Male"); // Romeo
    assertAttributes(5035, "Human", "Female"); // Juliet
    assertAttributes(4284, "Human", "Female"); // Aggie
    assertAttributes(3561, "Human", "Female"); // Veronica
    assertAttributes(1305, "Human", "Female"); // Hairdresser
    assertAttributes(11868, "Human", "Female"); // Aris (Gypsy)
    assertAttributes(3481, "Undead", "Male"); // Count Draynor
    assertAttributes(3893, "Dwarf", "Male"); // Doric
    assertAttributes(766, "Human", "Male"); // Banker
  }

  @Test
  public void femaleNamedTownsfolkResolveFemaleWithoutAnOverride() {
    // Names with no female title still resolve Female via the curated first-name
    // lexicon in the generator, so townsfolk like these don't default to male.
    assertAttributes(7284, "Human", "Female"); // Gertrude
    assertAttributes(3214, "Human", "Female"); // Cassie
  }

  @Test
  public void knownEntriesAreMarkedAsTableSourced() {
    NPCAttributes goblin = analyzer.lookup(655, "Goblin");
    assertEquals("StaticTable", goblin.getSource());
    assertEquals(655, goblin.getNpcId());
  }

  @Test
  public void unknownIdFallsBackToUnknownRaceSoFallbackVoiceApplies() {
    // Race must be Unknown (not Human) so VoiceManager routes through the configured fallback voice
    // rather than silently using the human voice and making the fallback toggle a no-op.
    NPCAttributes attributes = analyzer.lookup(987654321, "Totally Made Up NPC");
    assertNotNull(attributes);
    assertEquals("Unknown", attributes.getRace());
    assertEquals("Male", attributes.getGender());
    assertEquals("Default", attributes.getSource());
    assertEquals(987654321, attributes.getNpcId());
  }

  @Test
  public void unknownFemaleNamedNpcGetsFemaleFallbackGender() {
    // The lone runtime name check: an explicit female word picks the female fallback gender for
    // missing-id NPCs, keeping the "gender-appropriate human voice" fallback meaningful.
    assertEquals("Female", analyzer.lookup(987654322, "Mysterious Woman").getGender());
    assertEquals("Female", analyzer.lookup(987654323, "Lost Princess").getGender());
    // No female signal stays Male; a substring inside a larger word must not trigger it.
    assertEquals("Male", analyzer.lookup(987654324, "Old Sailor").getGender());
    assertEquals("Male", analyzer.lookup(987654325, "Womanizer Larry").getGender());
  }

  @Test
  public void unknownIdFallbackIsStable() {
    NPCAttributes first = analyzer.lookup(424242, "Unknown");
    NPCAttributes second = analyzer.lookup(424242, "Unknown");
    assertEquals(first.getRace(), second.getRace());
    assertEquals(first.getGender(), second.getGender());
  }

  @Test
  public void nullNpcReturnsNull() {
    assertNull(analyzer.analyzeNPC(null));
  }

  @Test
  public void lookupWorksWithoutInitializeUsingDefault() {
    // A fresh analyzer that was never initialized must still resolve safely (empty table ->
    // default)
    // rather than throwing, so a missing resource can never break voice selection.
    NPCDemographicAnalyzer fresh = new NPCDemographicAnalyzer();
    NPCAttributes attributes = fresh.lookup(101, "Goblin");
    assertEquals("Unknown", attributes.getRace());
    assertEquals("Male", attributes.getGender());
    assertEquals("Default", attributes.getSource());
  }

  private void assertAttributes(int npcId, String expectedRace, String expectedGender) {
    NPCAttributes attributes = analyzer.lookup(npcId, null);
    assertNotNull("expected a table entry for id " + npcId, attributes);
    assertEquals("race for id " + npcId, expectedRace, attributes.getRace());
    assertEquals("gender for id " + npcId, expectedGender, attributes.getGender());
  }
}
