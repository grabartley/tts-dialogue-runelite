package com.grahambartley.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.grahambartley.synthesis.CharacterProfile;
import org.junit.Test;

/**
 * Combining resolution: default + race + ethnicity + every matching keyword category + per-NPC id.
 */
public class NpcProfileTableTest {

  private static final String JSON =
      "{"
          + "\"default\":{\"name\":\"Default\",\"accent\":\"British RP.\",\"style\":\"Plain.\",\"pace\":\"Steady.\"},"
          + "\"player\":{\"name\":\"Adventurer\",\"style\":\"Brave hero.\"},"
          + "\"byRace\":{"
          + "\"Human\":{\"name\":\"Human\",\"accent\":\"British.\",\"style\":\"Ordinary.\"},"
          + "\"Troll\":{\"name\":\"Troll\",\"accent\":\"Brixton.\",\"style\":\"Big and dim.\"}},"
          + "\"byEthnicity\":{\"kharidian\":{\"accent\":\"Middle Eastern.\"}},"
          + "\"byCategory\":["
          + "{\"id\":\"vampyre\",\"keywords\":[\"vampyre\",\"vyre\"],\"name\":\"Vampyre\",\"accent\":\"Transylvanian.\",\"style\":\"Predatory.\"},"
          + "{\"id\":\"imp\",\"keywords\":[\"imp\"],\"name\":\"Imp\",\"style\":\"Squeaky.\"}"
          + "],"
          + "\"byId\":{\"_comment\":\"x\",\"100\":{\"name\":\"Vanstrom\",\"style\":\"An ancient vampyre lord.\"}}"
          + "}";

  private static NpcProfileTable table() {
    JsonObject profiles = new JsonParser().parse(JSON).getAsJsonObject();
    return NpcProfileTable.fromProfilesJson(profiles);
  }

  @Test
  public void noMatchingLayerFallsBackToTheCompleteDefault() {
    CharacterProfile p = table().resolveNpc(null, "Random Bloke", null, null).profile();
    assertEquals("Default", p.name());
    assertEquals("British RP.", p.accent());
    assertEquals("Plain.", p.style());
    assertEquals("Steady.", p.pace());
  }

  @Test
  public void raceLayerOverridesDefaultAndInheritsUnsetFields() {
    NpcProfileTable.Resolution r = table().resolveNpc(null, "Mountain Troll", "Troll", null);
    assertEquals("race:Troll", r.source());
    assertEquals("Troll", r.profile().name());
    assertEquals("Brixton.", r.profile().accent());
    assertEquals("Big and dim.", r.profile().style());
    assertEquals("pace falls through to the default", "Steady.", r.profile().pace());
  }

  @Test
  public void ethnicityAccentTintsPlainFolkOverTheRaceAccent() {
    // A desert human: ethnicity tints the accent over the human default, persona unchanged.
    NpcProfileTable.Resolution r = table().resolveNpc(null, "Desert Trader", "Human", "kharidian");
    assertEquals("race:Human+ethnicity:kharidian", r.source());
    assertEquals(
        "the ethnicity accent wins for plain folk", "Middle Eastern.", r.profile().accent());
    assertEquals(
        "style stays the race style (ethnicity is accent-only)", "Ordinary.", r.profile().style());
  }

  @Test
  public void ethnicityIsSkippedForDistinctiveRaces() {
    // A dwarf-equivalent (Troll here) in the desert keeps its racial accent, not the ethnicity's.
    NpcProfileTable.Resolution r = table().resolveNpc(null, "Desert Troll", "Troll", "kharidian");
    assertEquals("ethnicity is not applied to a distinctive race", "race:Troll", r.source());
    assertEquals("Brixton.", r.profile().accent());
  }

  @Test
  public void aKeywordCategoryStillBeatsTheEthnicityAccent() {
    // A vampyre in the desert: the distinctive category accent beats the ethnicity one.
    NpcProfileTable.Resolution r = table().resolveNpc(null, "Feral Vampyre", "Human", "kharidian");
    assertEquals("race:Human+ethnicity:kharidian+keyword:vampyre", r.source());
    assertEquals("Transylvanian.", r.profile().accent());
  }

  @Test
  public void raceAndCategoryCombineStyleWhileTheCategoryAccentWins() {
    NpcProfileTable.Resolution r = table().resolveNpc(null, "Vampyre Brute", "Troll", null);
    assertEquals("race:Troll+keyword:vampyre", r.source());
    assertEquals("the most specific name wins", "Vampyre", r.profile().name());
    assertEquals(
        "the category accent beats the race accent", "Transylvanian.", r.profile().accent());
    assertTrue("the race style is part of the blend", r.profile().style().contains("Big and dim."));
    assertTrue(
        "the category style is part of the blend", r.profile().style().contains("Predatory."));
  }

  @Test
  public void multipleCategoriesAllCombine() {
    NpcProfileTable.Resolution r = table().resolveNpc(null, "Imp Vampyre", null, null);
    assertEquals(
        "both categories appear in declaration order", "keyword:vampyre+keyword:imp", r.source());
    assertTrue(r.profile().style().contains("Predatory."));
    assertTrue(r.profile().style().contains("Squeaky."));
    assertEquals("the last category to set a name wins", "Imp", r.profile().name());
  }

  @Test
  public void perIdOverrideAddsOnTopAndWinsSingleValuedFields() {
    NpcProfileTable.Resolution r = table().resolveNpc(100, "Vampyre Vanstrom", "Undead", null);
    assertEquals("every match contributes", "keyword:vampyre+id:100", r.source());
    assertEquals("the bespoke name wins", "Vanstrom", r.profile().name());
    assertTrue(
        "the category style is still in the blend", r.profile().style().contains("Predatory."));
    assertTrue(
        "the bespoke style is added on top",
        r.profile().style().contains("An ancient vampyre lord."));
    assertEquals(
        "accent the id entry did not set inherits from the matched keyword layer",
        "Transylvanian.",
        r.profile().accent());
  }

  @Test
  public void raceMatchingIsCaseInsensitive() {
    assertEquals("Troll", table().resolveNpc(null, "x", "TROLL", null).profile().name());
    assertEquals("Troll", table().resolveNpc(null, "x", "troll", null).profile().name());
  }

  @Test
  public void keywordMatchingRespectsWordBoundaries() {
    assertEquals(
        "'imp' must not match inside 'important'",
        "Default",
        table().resolveNpc(null, "Important Person", null, null).profile().name());
    assertEquals(
        "'imp' matches the whole word",
        "Imp",
        table().resolveNpc(null, "Imp", null, null).profile().name());
  }

  @Test
  public void wordContainsIsBoundedOnBothSides() {
    assertTrue(NpcProfileTable.wordContains("feral vampyre", "vampyre"));
    assertTrue(NpcProfileTable.wordContains("tztok-jad", "jad"));
    assertFalse(NpcProfileTable.wordContains("important", "imp"));
    assertFalse(NpcProfileTable.wordContains("shrimp", "imp"));
  }

  @Test
  public void playerProfileLayersOverDefaultThenConfigOverridesNonBlankFields() {
    NpcProfileTable t = table();

    CharacterProfile base = t.resolvePlayer(null, null, null);
    assertEquals("Adventurer", base.name());
    assertEquals("the player style comes from the player layer", "Brave hero.", base.style());
    assertEquals("accent inherits from the default", "British RP.", base.accent());

    CharacterProfile overridden = t.resolvePlayer("Pirate drawl.", "   ", "");
    assertEquals("a non-blank accent overrides", "Pirate drawl.", overridden.accent());
    assertEquals("a blank style inherits", "Brave hero.", overridden.style());
    assertEquals("a blank pace inherits", "Steady.", overridden.pace());
  }
}
