package com.grahambartley;

import static org.junit.Assert.assertEquals;

import com.grahambartley.VoiceManager.NPCGender;
import com.grahambartley.VoiceManager.NPCRace;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Maps raw wiki/learned race and gender strings onto the voice enums. */
@RunWith(JUnitParamsRunner.class)
public class NpcDemographicParserTest {

  private Object[] raceCases() {
    return new Object[] {
      // Exact enum names map directly.
      new Object[] {"DWARF", NPCRace.DWARF},
      new Object[] {"goblin", NPCRace.GOBLIN},
      // Keyword variants bucket onto the nearest race.
      new Object[] {"Old man", NPCRace.HUMAN},
      new Object[] {"Elven warrior", NPCRace.ELF},
      new Object[] {"Imcando dwarf", NPCRace.DWARF},
      new Object[] {"Gnome child", NPCRace.GOBLIN},
      new Object[] {"Mountain giant", NPCRace.TROLL},
      new Object[] {"Skeleton mage", NPCRace.UNDEAD},
      new Object[] {"Restless ghost", NPCRace.UNDEAD},
      new Object[] {"Green dragon", NPCRace.DEMON},
      new Object[] {"Jungle gorilla", NPCRace.GORILLA},
      new Object[] {"Karamja primate", NPCRace.MONKEY},
      new Object[] {"Battle mage", NPCRace.WIZARD},
      new Object[] {"Tortuga elder", NPCRace.TORTUGAN},
      // Unknown or empty falls through to UNKNOWN.
      new Object[] {null, NPCRace.UNKNOWN},
      new Object[] {"", NPCRace.UNKNOWN},
      new Object[] {"Penguin", NPCRace.UNKNOWN},
    };
  }

  @Test
  @Parameters(method = "raceCases")
  public void mapsRawRaceToEnum(String raw, NPCRace expected) {
    assertEquals(expected, NpcDemographicParser.toRace(raw));
  }

  private Object[] genderCases() {
    return new Object[] {
      new Object[] {"FEMALE", NPCGender.FEMALE},
      new Object[] {"Woman", NPCGender.FEMALE},
      new Object[] {"Noble lady", NPCGender.FEMALE},
      new Object[] {"Old man", NPCGender.MALE},
      new Object[] {"Lord of the manor", NPCGender.MALE},
      // Empty is UNKNOWN; an unrecognised non-empty value defaults to MALE.
      new Object[] {null, NPCGender.UNKNOWN},
      new Object[] {"", NPCGender.UNKNOWN},
      new Object[] {"indeterminate", NPCGender.MALE},
    };
  }

  @Test
  @Parameters(method = "genderCases")
  public void mapsRawGenderToEnum(String raw, NPCGender expected) {
    assertEquals(expected, NpcDemographicParser.toGender(raw));
  }
}
