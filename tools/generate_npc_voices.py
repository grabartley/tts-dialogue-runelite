#!/usr/bin/env python3
"""Offline generator for the bundled NPC voice lookup table.

This is one-time offline tooling. It is NOT part of the plugin's runtime path:
the plugin only reads the generated JSON resource and does a single in-memory
map lookup keyed by NPC id. Regenerate the table whenever you want to expand or
correct coverage, then commit the updated resource.

Why a FULL NPC source (not just monsters)
-----------------------------------------
The plugin's whole point is to voice the peaceful, dialogue NPCs players talk
to (Hans, the Lumbridge Guide, bankers, the Cook, shopkeepers, quest givers).
Those NPCs are NOT in an attackable-monsters dump, so a monsters-only table
collapses them all to the runtime default (Human/Male) and male and female
townsfolk end up sounding identical. To fix that we source from a FULL NPC
dataset (every NPC definition, id -> name), classify race/gender from the name
with a deterministic, auditable keyword classifier, and keep EVERY entry,
including plain Human/Male, so townsfolk get an explicit, correct gender.

Pipeline
--------
  1. Load a full NPC summary (real npcId -> name for every NPC). This is the
     coverage backbone and includes peaceful/quest/shop/town NPCs.
  2. Optionally load an OSRSBox monster dump for the richer ``examine`` text it
     carries on attackable NPCs, which sharpens race/gender on monsters.
  3. Classify each entry's race/gender with a deterministic keyword classifier
     (name + any examine text). Unlike the old generator we do NOT drop entries
     that resolve to the Human/Male default: those are exactly the townsfolk we
     want present so the correct gender is pinned.
  4. Merge the hand-curated overrides on top. Overrides are authoritative and
     always win, so high-traffic named NPCs (whose gender can't be read off the
     name) and any corrections can be pinned by id.
  5. Emit npcId -> {race, gender} as src/main/resources/npc-voices.json.

Data sources
------------
Default full NPC summary: the osrsreboxed-db ``npcs-summary.json`` (a community
fork of the now-defunct OSRSBox DB), which publishes id+name for every NPC
definition. The OSRSBox ``monsters-complete.json`` dump is still used, when
reachable, only to enrich monster race/gender via examine text; it is optional.

Usage
-----
  # Download both sources automatically and write the bundled resource:
  python3 tools/generate_npc_voices.py

  # Or point at local dumps and/or a custom output path:
  python3 tools/generate_npc_voices.py \
      --npcs /path/to/npcs-summary.json \
      --monsters /path/to/monsters-complete.json \
      --overrides tools/overrides.json \
      --out src/main/resources/npc-voices.json

The classifier is intentionally simple and auditable. When it gets something
wrong for an important NPC, fix it in tools/overrides.json (authoritative)
rather than piling on fragile heuristics here.
"""

import argparse
import json
import os
import re
import sys
import urllib.request

# Full NPC summary (id -> name for every NPC), from the osrsreboxed-db fork.
DEFAULT_NPCS_URL = (
    "https://raw.githubusercontent.com/0xNeffarion/osrsreboxed-db/master/"
    "docs/npcs-summary.json"
)
# Optional monster dump, used only for the richer examine text it carries.
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
        r"banshee|spectre|wraith|zombi|reanimat|ankou|vampyre|vampire|"
        r"\bvyre\b|vyrewatch|\bbones\b|\bgrave\b|\bcrypt\b",
        "Undead",
    ),
    (r"hobgoblin|goblin|gnome", "Goblin"),
    (r"dwarf|dwarven|imcando", "Dwarf"),
    (r"\belf\b|\belves\b|elven|elf ", "Elf"),
    (r"troll|\bgiant\b|giants\b|cyclops|ogre|ent\b|\bgolem\b", "Troll"),
    (r"wizard|sorcerer|sorceress|necromancer|\bmage\b|\bmagi\b", "Wizard"),
]
RACE_RULES = [(re.compile(p, re.IGNORECASE), race) for p, race in RACE_RULES]

# Female name/title keywords (word-aware). Default gender is Male; an entry is
# only marked Female on a clear signal. Keep this list in sync with the runtime
# FEMALE_NAME_HINT in NPCDemographicAnalyzer.java (the runtime fallback mirrors
# these words for ids that are missing from the table).
FEMALE_PATTERN = re.compile(
    r"\bwoman\b|\bwomen\b|\bgirl\b|\blady\b|\bladies\b|\bqueen\b|\bprincess\b|"
    r"\bduchess\b|\bcountess\b|\bbaroness\b|\bempress\b|\bwitch\b|"
    r"\bsorceress\b|\bpriestess\b|\bhuntress\b|\bbanshee\b|\bhag\b|"
    r"\bcrone\b|\bmother\b|\bsister\b|\bnun\b|\bmaiden\b|\bbarmaid\b|"
    r"\bwaitress\b|\bseamstress\b|\bgoddess\b|\bmistress\b|\bmadam\b|"
    r"\bdamsel\b|\bwife\b|\bmaid\b|\bgirls\b|\baunt\b|\bauntie\b|\bgranny\b|"
    r"\bnanny\b|\bbride\b|\bmrs\b|\bms\b|\bmiss\b|\bdame\b|\bgirlfriend\b|"
    r"\bdaughter\b|\bniece\b|\bwidow\b|\bmum\b|\bmom\b|\bgirly\b",
    re.IGNORECASE,
)
# Male name/title keywords. Default gender is already Male, but matching these
# explicitly lets the meta counts reflect deliberate male titles and keeps the
# two halves of the classifier symmetric/auditable.
MALE_PATTERN = re.compile(
    r"\bman\b|\bmen\b|\bboy\b|\bboys\b|\bking\b|\blord\b|\bprince\b|\bduke\b|"
    r"\bsir\b|\bbaron\b|\bemperor\b|\bfather\b|\bbrother\b|\bmonk\b|"
    r"\bwizard\b|\bguardsman\b|\bswordsman\b|\bfisherman\b|\bcraftsman\b|"
    r"\bhuntsman\b|\bgentleman\b|\bmr\b|\bmister\b|\bson\b|\buncle\b|"
    r"\bgrandfather\b|\bgrandpa\b|\bnephew\b|\bgroom\b|\bhusband\b|\bdad\b|"
    r"\bpriest\b|\bbishop\b|\bfriar\b|\bpope\b|\bguy\b|\bfellow\b|\blad\b",
    re.IGNORECASE,
)

# Conservative, curated set of unambiguous female FIRST names that appear on
# human NPCs (townsfolk, quest givers) whose names carry no female title. This
# is an explicit, reviewable allowlist, not a fuzzy heuristic: it only fires on
# the FIRST token of a name, only when no race keyword and no gendered title
# matched, and only for entries that look human (no race signal). Ambiguous or
# unisex names are deliberately omitted; pin those in overrides.json instead.
FEMALE_FIRST_NAMES = {
    "ada", "agnes", "aggie", "alice", "amber", "amelia", "anna", "annabel",
    "ariane", "arianwyn", "astrid", "aubrey", "barbara", "beatrice", "becky",
    "bella", "betty", "brenda", "bridget", "brunhild", "carol", "caroline",
    "cassie", "catherine", "charlotte", "chelsea", "christine", "clara",
    "clarabel", "claudia", "cynthia", "daisy", "dawn", "diana", "diane",
    "dorothy", "doris", "edith", "eleanor", "elena", "ellen", "elspeth",
    "emily", "emma", "esther", "eva", "evelyn", "fara", "fiona", "florence",
    "frenita", "gabrielle", "gertrude", "gillie", "grace", "greta", "gudrun",
    "hannah", "helen", "henrietta", "hetty", "hilda", "ingrid", "irene",
    "isabel", "isabella", "jane", "janet", "jennifer", "jenny", "jessica",
    "joan", "josephine", "judith", "julia", "juliana", "juliet", "katherine",
    "katie", "kaylee", "laura", "lily", "linda", "lisa", "lucy", "lydia",
    "mabel", "maggie", "margaret", "maria", "marie", "marlene", "martha",
    "mary", "matilda", "maude", "meg", "melanie", "michelle", "mildred",
    "millie", "miriam", "molly", "nancy", "nina", "nora", "olivia", "patricia",
    "pauline", "penelope", "phyllis", "polly", "priscilla", "rachel",
    "rebecca", "rosie", "rose", "ruth", "sabrina", "sandra", "sarah", "selena",
    "sophia", "sophie", "stephanie", "susan", "tabitha", "tegana", "theresa",
    "valerie", "vanessa", "vera", "veronica", "victoria", "vivian", "wendy",
    "wilma", "yvonne", "zara",
}
FIRST_TOKEN = re.compile(r"[A-Za-z']+")


def classify_race(name, examine=None):
    """Return a race string for a name/examine, or None when there is no strong
    signal. Human-looking NPCs intentionally return None here and are filled in
    by the caller with the Human default; only distinctive creatures match."""
    text = " ".join(filter(None, [name, examine]))
    if not text:
        return None
    for regex, race in RACE_RULES:
        if regex.search(text):
            return race
    return None


def classify_gender(name, examine=None, race=None):
    """Return 'Female', 'Male', or None when there is no strong signal.

    Order of signals: gendered title/word (female then male), then, only for
    human-looking NPCs (no race signal), the curated female first-name allowlist
    on the first token. The first-name check is deliberately last and narrow so
    it never overrides an explicit title or mislabels a creature.
    """
    text = " ".join(filter(None, [name, examine]))
    if not text:
        return None
    if FEMALE_PATTERN.search(text):
        return "Female"
    if MALE_PATTERN.search(text):
        return "Male"
    if race is None and name:
        match = FIRST_TOKEN.search(name)
        if match and match.group(0).lower() in FEMALE_FIRST_NAMES:
            return "Female"
    return None


def load_json(path_or_url, optional=False):
    try:
        if re.match(r"^https?://", path_or_url):
            print(f"Downloading {path_or_url} ...", file=sys.stderr)
            with urllib.request.urlopen(path_or_url, timeout=120) as resp:
                return json.loads(resp.read().decode("utf-8"))
        with open(path_or_url, "r", encoding="utf-8") as fh:
            return json.load(fh)
    except Exception as exc:  # noqa: BLE001 - tooling, surface and continue
        if optional:
            print(f"  (skipping optional source {path_or_url}: {exc})", file=sys.stderr)
            return None
        raise


def iter_npcs(summary):
    """Yield (npc_id:int, name:str) from a full NPC summary.

    Accepts either a dict keyed by id (osrsreboxed npcs-summary.json) or a list
    of {id, name} records, so the generator is robust to either layout.
    """
    records = summary.values() if isinstance(summary, dict) else summary
    for entry in records:
        if not isinstance(entry, dict):
            continue
        raw_id = entry.get("id")
        try:
            npc_id = int(raw_id)
        except (TypeError, ValueError):
            continue
        name = entry.get("name") or ""
        if not name or name.lower() == "null":
            continue
        yield npc_id, name


def build_table(npcs_summary, monsters, overrides):
    """Build the npcId -> {race, gender} table.

    Every named NPC from the full summary is kept (including plain Human/Male
    townsfolk), so the correct gender is always pinned. Examine text from the
    optional monster dump sharpens race/gender on attackable creatures.
    Overrides are applied last and always win.
    """
    monster_examine = {}
    if monsters:
        for key, entry in monsters.items():
            try:
                monster_examine[int(key)] = entry.get("examine") or ""
            except (TypeError, ValueError):
                continue

    table = {}
    human_male_default = 0
    for npc_id, name in iter_npcs(npcs_summary):
        examine = monster_examine.get(npc_id, "")
        race = classify_race(name, examine)
        gender = classify_gender(name, examine, race=race)
        if race is None and gender is None:
            human_male_default += 1
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

    return table, human_male_default, len(override_npcs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--npcs", default=DEFAULT_NPCS_URL,
                        help="Path or URL to the full NPC summary (id -> name).")
    parser.add_argument("--monsters", default=DEFAULT_MONSTERS_URL,
                        help="Path or URL to the OSRSBox monsters dump "
                             "(optional, only used for examine text).")
    parser.add_argument("--overrides", default=DEFAULT_OVERRIDES,
                        help="Path to the curated overrides JSON.")
    parser.add_argument("--out", default=DEFAULT_OUT,
                        help="Output path for the bundled resource.")
    args = parser.parse_args()

    npcs_summary = load_json(args.npcs)
    monsters = load_json(args.monsters, optional=True)
    overrides = load_json(args.overrides)

    table, human_male_default, override_count = build_table(
        npcs_summary, monsters, overrides
    )

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
                           "tools/generate_npc_voices.py from a FULL NPC source so "
                           "peaceful dialogue NPCs are covered. Do not hand-edit; "
                           "edit tools/overrides.json and regenerate instead.",
            "schema": "npcs[id] = { race, gender }",
            "source": "osrsreboxed-db npcs-summary.json (full NPC list) + curated "
                      "tools/overrides.json; OSRSBox monsters-complete.json examine "
                      "text used opportunistically when reachable.",
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
    print(f"  ({human_male_default} entries used the Human/Male default)",
          file=sys.stderr)


if __name__ == "__main__":
    main()
