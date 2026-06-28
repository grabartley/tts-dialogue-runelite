package com.grahambartley.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.grahambartley.synthesis.CharacterProfile;
import org.junit.Before;
import org.junit.Test;

/**
 * Validates the character profiles actually bundled in {@code /npc-voices.json}: the section loads,
 * every race bucket resolves, the stated special accents hold (race, ethnicity, and keyword), and
 * the player and a bespoke NPC resolve. Guards against a malformed or regenerated resource shipping
 * broken profiles.
 */
public class NpcProfilesResourceTest {

  private NpcProfileTable table;

  @Before
  public void setUp() {
    table = new NpcProfileTable();
    table.initialize();
  }

  @Test
  public void theBundledProfilesSectionLoads() {
    assertTrue("the bundled profiles section loaded", table.isLoaded());
  }

  @Test
  public void everyRaceBucketResolvesToItsOwnLayer() {
    for (String race :
        new String[] {"Human", "Elf", "Dwarf", "Goblin", "Troll", "Undead", "Demon", "Wizard"}) {
      assertEquals(
          "race " + race + " resolves to its own bucket",
          "race:" + race,
          table.resolveNpc(null, "someone", race, null).source());
    }
  }

  @Test
  public void everythingDefaultsToABritishAccent() {
    CharacterProfile p = table.resolveNpc(null, "A Nameless Stranger", null, null).profile();
    assertTrue("the default accent is British", p.accent().contains("British"));
  }

  @Test
  public void statedSpecialAccentsHold() {
    assertTrue(
        "trolls sound South London / Brixton",
        table
            .resolveNpc(null, "Mountain Troll", "Troll", null)
            .profile()
            .accent()
            .contains("Brixton"));
    assertTrue(
        "dwarves sound Scottish",
        table
            .resolveNpc(null, "Dwarf Miner", "Dwarf", null)
            .profile()
            .accent()
            .contains("Scottish"));
    assertTrue(
        "gnomes sound country Irish",
        table.resolveNpc(null, "Gnome Child", "Gnome", null).profile().accent().contains("Irish"));
    assertTrue(
        "leprechauns sound Irish",
        table
            .resolveNpc(null, "Tool Leprechaun", "Human", null)
            .profile()
            .accent()
            .contains("Irish"));
    assertTrue(
        "vampyres sound Transylvanian / Dracula-esque",
        table
            .resolveNpc(null, "Feral Vampyre", "Undead", null)
            .profile()
            .accent()
            .contains("Transylvanian"));
  }

  @Test
  public void ethnicityAccentsHoldFromTheBundledTable() {
    assertTrue(
        "Kharidian desert locals sound Middle Eastern",
        table
            .resolveNpc(null, "Desert Trader", "Human", "kharidian")
            .profile()
            .accent()
            .contains("Middle Eastern"));
    assertTrue(
        "Menaphite locals sound Egyptian",
        table
            .resolveNpc(null, "Citizen", "Human", "menaphite")
            .profile()
            .accent()
            .contains("Egyptian"));
    assertTrue(
        "Karamja locals sound West African",
        table
            .resolveNpc(null, "Trader", "Human", "karamja")
            .profile()
            .accent()
            .contains("African"));
    assertTrue(
        "Fremennik locals sound Norse",
        table
            .resolveNpc(null, "Villager", "Human", "fremennik")
            .profile()
            .accent()
            .contains("Norse"));
  }

  @Test
  public void aBespokePerNpcProfileResolvesByIdFromTheBundledTable() {
    NpcProfileTable.Resolution r = table.resolveNpc(3105, "Hans", "Human", null);
    assertTrue("the bespoke id contributes to the blend", r.source().contains("id:3105"));
    assertEquals("the bespoke name wins", "Hans", r.profile().name());
  }

  @Test
  public void thePlayerProfileResolvesFromTheBundledTable() {
    CharacterProfile p = table.resolvePlayer(null, null, null);
    assertTrue("the player has a name label", p.name() != null && !p.name().isEmpty());
    assertTrue("the player accent is British by default", p.accent().contains("British"));
  }
}
