package com.grahambartley.data;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Represents demographic attributes for an NPC including race and gender information. */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class NPCAttributes {

  /** The NPC's race (e.g., Human, Elf, Dwarf, etc.) */
  private String race;

  /** The NPC's gender (Male, Female, Unknown) */
  private String gender;

  /** The NPC's ID in the game */
  private int npcId;

  /** The NPC's name */
  private String name;

  /** Source of this information (e.g., "OSRSBox", "Wiki", "Inference", "Manual") */
  private String source;

  /** Confidence level of the classification (0.0 to 1.0) */
  private double confidence;

  /** Additional notes or context about the classification */
  private String notes;

  /** Constructor for basic race/gender classification */
  public NPCAttributes(String race, String gender) {
    this.race = race;
    this.gender = gender;
    this.confidence = 0.5; // Default medium confidence
    this.source = "Inference";
  }

  /** Constructor with confidence and source */
  public NPCAttributes(String race, String gender, String source, double confidence) {
    this.race = race;
    this.gender = gender;
    this.source = source;
    this.confidence = confidence;
  }

  /** Check if this attributes instance has valid race information */
  public boolean hasValidRace() {
    return race != null && !race.isEmpty() && !"Unknown".equalsIgnoreCase(race);
  }

  /** Check if this attributes instance has valid gender information */
  public boolean hasValidGender() {
    return gender != null && !gender.isEmpty() && !"Unknown".equalsIgnoreCase(gender);
  }

  /** Check if this is a high-confidence classification */
  public boolean isHighConfidence() {
    return confidence >= 0.8;
  }

  /** Check if this is a low-confidence classification that should be double-checked */
  public boolean isLowConfidence() {
    return confidence < 0.3;
  }
}
