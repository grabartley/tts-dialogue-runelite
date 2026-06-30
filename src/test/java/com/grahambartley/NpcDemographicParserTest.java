package com.grahambartley;

import static org.junit.Assert.assertEquals;

import com.grahambartley.VoiceManager.NPCGender;
import com.grahambartley.VoiceManager.NPCRace;
import org.junit.Test;

/** Maps raw wiki/learned race and gender strings onto the voice enums. */
public class NpcDemographicParserTest {

  @Test
  public void exactEnumNamesMapDirectly() {
    assertEquals(NPCRace.DWARF, NpcDemographicParser.toRace("DWARF"));
    assertEquals(NPCRace.GOBLIN, NpcDemographicParser.toRace("goblin"));
    assertEquals(NPCGender.FEMALE, NpcDemographicParser.toGender("FEMALE"));
  }

  @Test
  public void raceKeywordsBucketCommonVariants() {
    assertEquals(NPCRace.HUMAN, NpcDemographicParser.toRace("Old man"));
    assertEquals(NPCRace.ELF, NpcDemographicParser.toRace("Elven warrior"));
    assertEquals(NPCRace.DWARF, NpcDemographicParser.toRace("Imcando dwarf"));
    assertEquals(NPCRace.GOBLIN, NpcDemographicParser.toRace("Gnome child"));
    assertEquals(NPCRace.TROLL, NpcDemographicParser.toRace("Mountain giant"));
    assertEquals(NPCRace.UNDEAD, NpcDemographicParser.toRace("Skeleton mage"));
    assertEquals(NPCRace.UNDEAD, NpcDemographicParser.toRace("Restless ghost"));
    assertEquals(NPCRace.DEMON, NpcDemographicParser.toRace("Green dragon"));
    assertEquals(NPCRace.GORILLA, NpcDemographicParser.toRace("Jungle gorilla"));
    assertEquals(NPCRace.MONKEY, NpcDemographicParser.toRace("Karamja primate"));
    assertEquals(NPCRace.WIZARD, NpcDemographicParser.toRace("Battle mage"));
    assertEquals(NPCRace.TORTUGAN, NpcDemographicParser.toRace("Tortuga elder"));
  }

  @Test
  public void unknownOrEmptyRaceIsUnknown() {
    assertEquals(NPCRace.UNKNOWN, NpcDemographicParser.toRace(null));
    assertEquals(NPCRace.UNKNOWN, NpcDemographicParser.toRace(""));
    assertEquals(NPCRace.UNKNOWN, NpcDemographicParser.toRace("Penguin"));
  }

  @Test
  public void genderKeywordsBucketCommonVariants() {
    assertEquals(NPCGender.FEMALE, NpcDemographicParser.toGender("Woman"));
    assertEquals(NPCGender.FEMALE, NpcDemographicParser.toGender("Noble lady"));
    assertEquals(NPCGender.MALE, NpcDemographicParser.toGender("Old man"));
    assertEquals(NPCGender.MALE, NpcDemographicParser.toGender("Lord of the manor"));
  }

  @Test
  public void emptyGenderIsUnknownButUnrecognisedDefaultsToMale() {
    assertEquals(NPCGender.UNKNOWN, NpcDemographicParser.toGender(null));
    assertEquals(NPCGender.UNKNOWN, NpcDemographicParser.toGender(""));
    assertEquals(NPCGender.MALE, NpcDemographicParser.toGender("indeterminate"));
  }
}
