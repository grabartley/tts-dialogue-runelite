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
    assertAttributes(1, "Human", "Male"); // Man
    assertAttributes(2, "Human", "Female"); // Woman
    assertAttributes(101, "Goblin", "Male"); // Goblin
    assertAttributes(70, "Undead", "Male"); // Skeleton
    assertAttributes(216, "Dwarf", "Male"); // Thurgo
    assertAttributes(2293, "Elf", "Female"); // Arwen
    assertAttributes(142, "Demon", "Male"); // Demon
    assertAttributes(419, "Wizard", "Male"); // Wise Old Man
  }

  @Test
  public void knownEntriesAreMarkedAsTableSourced() {
    NPCAttributes goblin = analyzer.lookup(101, "Goblin");
    assertEquals("StaticTable", goblin.getSource());
    assertEquals(101, goblin.getNpcId());
  }

  @Test
  public void unknownIdFallsBackToDeterministicDefault() {
    NPCAttributes attributes = analyzer.lookup(987654321, "Totally Made Up NPC");
    assertNotNull(attributes);
    assertEquals("Human", attributes.getRace());
    assertEquals("Male", attributes.getGender());
    assertEquals("Default", attributes.getSource());
    assertEquals(987654321, attributes.getNpcId());
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
    assertEquals("Human", attributes.getRace());
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
