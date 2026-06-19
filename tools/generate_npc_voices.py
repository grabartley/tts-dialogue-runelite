#!/usr/bin/env python3
"""Offline generator for the bundled NPC voice lookup table.

This is one-time offline tooling. It is NOT part of the plugin's runtime path:
the plugin only reads the generated JSON resource and does a single in-memory
map lookup keyed by NPC id. Regenerate the table whenever you want to expand or
correct coverage, then commit the updated resource.

Pipeline:
  1. Load a static OSRSBox monster dump (real npcId -> name/examine data). The
     dump only covers attackable NPCs, so peaceful NPCs come from overrides.
  2. Classify each entry's race/gender with a deterministic, conservative
     keyword classifier (strong signals only). Ambiguous entries that would just
     resolve to the runtime default (Human/Male) are dropped to keep the table
     meaningful and small.
  3. Merge the hand-curated overrides on top. Overrides are authoritative and
     always win, so corrections and important peaceful NPCs can be pinned by id.
  4. Emit npcId -> {race, gender} as src/main/resources/npc-voices.json.

Usage:
  # Download the dump automatically and write the bundled resource:
  python3 tools/generate_npc_voices.py

  # Or point at a local dump and/or custom output path:
  python3 tools/generate_npc_voices.py \
      --monsters /path/to/monsters-complete.json \
      --overrides tools/overrides.json \
      --out src/main/resources/npc-voices.json

The classifier is intentionally simple and auditable. When it gets something
wrong, fix it in tools/overrides.json (authoritative) rather than adding fragile
heuristics here.
"""

import argparse
import json
import os
import re
import sys
import urllib.request

DEFAULT_MONSTERS_URL = (
    "https://raw.githubusercontent.com/osrsbox/osrsbox-db/master/"
    "docs/monsters-complete.json"
)
DEFAULT_OUT = os.path.join("src", "main", "resources", "npc-voices.json")
DEFAULT_OVERRIDES = os.path.join("tools", "overrides.json")

VALID_RACES = {"Human", "Elf", "Dwarf", "Goblin", "Troll", "Undead", "Demon", "Wizard"}
VALID_GENDERS = {"Male", "Female"}

# Race keyword rules, checked in order. Each rule is (compiled word-aware regex,
# race). The first match wins, so list the more specific races first. Patterns
# use word boundaries to avoid false positives (e.g. "elf" must not match
# "self", "imp" must not match "important").
#
# Buckets are voice-categorical, not lore-accurate: there are only the 8 voice
# profiles in VoiceProfile, so lore-distinct creatures are mapped to the closest
# available voice (e.g. dragon/wyvern/kalphite -> Demon, gnome -> Goblin,
# cyclops/ogre/ent/golem -> Troll). Don't "fix" these to lore races; they're
# deliberate Kokoro speaker-slot assignments.
RACE_RULES = [
    (r"demon|devil|\bimp\b|imps\b|abyssal|hellhound|pyrefiend|nechryael", "Demon"),
    (r"dragon|wyvern|wyrm|drake|kalphite", "Demon"),
    (
        r"skeleton|zombie|ghost|ghoul|undead|wight|shade|revenant|mummy|"
        r"banshee|spectre|wraith|zombi|reanimat|ankou|vampyre|vampire",
        "Undead",
    ),
    (r"hobgoblin|goblin|gnome", "Goblin"),
    (r"dwarf|dwarven|imcando", "Dwarf"),
    (r"\belf\b|\belves\b|elven|elf ", "Elf"),
    (r"troll|\bgiant\b|giants\b|cyclops|ogre|ent\b|\bgolem\b", "Troll"),
    (r"wizard|sorcerer|sorceress|mage\b|necromancer", "Wizard"),
]
RACE_RULES = [(re.compile(p, re.IGNORECASE), race) for p, race in RACE_RULES]

# Female name/title keywords (word-aware). Default gender is Male; an entry is
# only marked Female on a clear signal.
FEMALE_PATTERN = re.compile(
    r"\bwoman\b|\bwomen\b|\bgirl\b|\blady\b|\bqueen\b|\bprincess\b|"
    r"\bduchess\b|\bcountess\b|\bbaroness\b|\bempress\b|\bwitch\b|"
    r"\bsorceress\b|\bpriestess\b|\bhuntress\b|\bbanshee\b|\bhag\b|"
    r"\bcrone\b|\bmother\b|\bsister\b|\bnun\b|\bmaiden\b|\bbarmaid\b|"
    r"\bwaitress\b|\bseamstress\b|\bgoddess\b|\bmistress\b|\bmadam\b|"
    r"\bdamsel\b|\bwife\b|\bmaid\b",
    re.IGNORECASE,
)
# Male name/title keywords, used only as a tie-break signal so an explicitly
# male title doesn't get dropped as an unsignalled default.
MALE_PATTERN = re.compile(
    r"\bman\b|\bboy\b|\bking\b|\blord\b|\bprince\b|\bduke\b|\bsir\b|"
    r"\bbaron\b|\bemperor\b|\bfather\b|\bbrother\b|\bmonk\b|\bwizard\b|"
    r"\bguardsman\b|\bswordsman\b",
    re.IGNORECASE,
)


def classify_race(name):
    """Return a race string for a name, or None when there is no strong signal."""
    if not name:
        return None
    for regex, race in RACE_RULES:
        if regex.search(name):
            return race
    return None


def classify_gender(name, examine):
    """Return 'Female', 'Male', or None when there is no strong signal."""
    text = " ".join(filter(None, [name, examine]))
    if FEMALE_PATTERN.search(text):
        return "Female"
    if MALE_PATTERN.search(text):
        return "Male"
    return None


def load_json(path_or_url):
    if re.match(r"^https?://", path_or_url):
        print(f"Downloading {path_or_url} ...", file=sys.stderr)
        with urllib.request.urlopen(path_or_url, timeout=120) as resp:
            return json.loads(resp.read().decode("utf-8"))
    with open(path_or_url, "r", encoding="utf-8") as fh:
        return json.load(fh)


def build_table(monsters, overrides):
    """Build the npcId -> {race, gender} table.

    Monster entries are only kept when they classify to something other than the
    runtime default (Human/Male), so the table stays meaningful. Overrides are
    applied last and always win.
    """
    table = {}
    kept_default = 0
    for key, entry in monsters.items():
        try:
            npc_id = int(key)
        except (TypeError, ValueError):
            continue
        name = entry.get("name") or ""
        examine = entry.get("examine") or ""

        race = classify_race(name)
        gender = classify_gender(name, examine)

        # Skip entries with no distinctive signal: the runtime default already
        # resolves them to Human/Male, so storing them adds nothing.
        if race is None and gender is None:
            kept_default += 1
            continue

        table[npc_id] = {
            "race": race or "Human",
            "gender": gender or "Male",
        }

    override_npcs = overrides.get("npcs", {})
    for key, entry in override_npcs.items():
        npc_id = int(key)
        race = entry["race"]
        gender = entry["gender"]
        if race not in VALID_RACES:
            raise ValueError(f"Override {key} has invalid race '{race}'")
        if gender not in VALID_GENDERS:
            raise ValueError(f"Override {key} has invalid gender '{gender}'")
        table[npc_id] = {"race": race, "gender": gender}

    return table, kept_default, len(override_npcs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--monsters", default=DEFAULT_MONSTERS_URL,
                        help="Path or URL to the OSRSBox monsters dump.")
    parser.add_argument("--overrides", default=DEFAULT_OVERRIDES,
                        help="Path to the curated overrides JSON.")
    parser.add_argument("--out", default=DEFAULT_OUT,
                        help="Output path for the bundled resource.")
    args = parser.parse_args()

    monsters = load_json(args.monsters)
    overrides = load_json(args.overrides)

    table, kept_default, override_count = build_table(monsters, overrides)

    # Stable, id-sorted output for clean diffs when regenerated.
    npcs = {str(npc_id): table[npc_id] for npc_id in sorted(table)}

    race_counts = {}
    gender_counts = {}
    for v in table.values():
        race_counts[v["race"]] = race_counts.get(v["race"], 0) + 1
        gender_counts[v["gender"]] = gender_counts.get(v["gender"], 0) + 1

    out = {
        "_meta": {
            "description": "Static precomputed npcId -> {race, gender} lookup baked "
                           "into the plugin. Generated offline by "
                           "tools/generate_npc_voices.py. Do not hand-edit; edit "
                           "tools/overrides.json and regenerate instead.",
            "schema": "npcs[id] = { race, gender }",
            "count": len(npcs),
            "overrides_applied": override_count,
            "race_counts": dict(sorted(race_counts.items())),
            "gender_counts": dict(sorted(gender_counts.items())),
        },
        "npcs": npcs,
    }

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as fh:
        json.dump(out, fh, indent=2, ensure_ascii=False)
        fh.write("\n")

    print(f"Wrote {len(npcs)} NPC entries to {args.out}", file=sys.stderr)
    print(f"  races:   {out['_meta']['race_counts']}", file=sys.stderr)
    print(f"  genders: {out['_meta']['gender_counts']}", file=sys.stderr)
    print(f"  ({kept_default} unsignalled monster entries left to runtime default)",
          file=sys.stderr)


if __name__ == "__main__":
    main()
