#!/usr/bin/env python3
"""Offline generator for the bundled NPC voice + profile lookup table.

This is one-time offline tooling. It is NOT part of the plugin's runtime path:
the plugin only reads the generated JSON resource and does in-memory map lookups
keyed by NPC id. Regenerate whenever you want to refresh coverage, then commit
the updated resource.

Data source: the Old School RuneScape Wiki (https://oldschool.runescape.wiki),
which is authoritative and current. Every NPC page transcludes
``Template:Infobox NPC`` and exposes ``race``, ``gender``, ``leagueRegion``,
``location`` and one or more cache ``id``s. We enumerate those pages, parse the
infoboxes, and map each id -> {race, gender, ethnicity}. This replaces the older
heuristic name classifier: race and gender now come straight from the wiki, so
townsfolk get the correct gender (e.g. Cecilia is Female) and newer NPCs
(Varlamore, etc.) are covered as soon as the wiki documents them.

Pipeline
--------
  1. Enumerate every page in the main namespace that transcludes
     ``Template:Infobox NPC``.
  2. Fetch each page's lead wikitext (where the infobox lives) in batches.
  3. Parse every infobox: collect all cache ids and the page's race, gender,
     leagueRegion and location.
  4. Map the wiki race onto one of the eight voice buckets, normalise gender,
     and map leagueRegion (+ location for the Menaphite cities) onto a region
     accent key.
  5. Merge the hand-curated overrides on top (authoritative, always win).
  6. Embed tools/profiles.json under the ``profiles`` key and emit
     src/main/resources/npc-voices.json.

Usage
-----
  python3 tools/generate_npc_voices.py
  python3 tools/generate_npc_voices.py --limit 500   # quick partial run for testing
"""

import argparse
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request

WIKI_API = "https://oldschool.runescape.wiki/api.php"
USER_AGENT = "tts-dialogue-runelite NPC table generator (contact: grabartley@gmail.com)"
# Talkable NPCs use Infobox NPC (carries race/gender/leagueRegion); talkable creatures use Infobox
# Monster (carries none of those), so for Monster pages race comes from the page's categories.
INFOBOX_TEMPLATES = ["Template:Infobox NPC", "Template:Infobox Monster"]

# Wiki page-category substring -> voice bucket, checked in order, first match wins. This is how
# Infobox Monster NPCs (trolls like Kob, ghosts, TzHaar, ...) get a race the infobox does not carry.
CATEGORY_RACE_RULES = [
    ("vampyre", "Undead"), ("vyre", "Undead"), ("ghost", "Undead"), ("skeleton", "Undead"),
    ("zombie", "Undead"), ("ghoul", "Undead"), ("undead", "Undead"), ("shade", "Undead"),
    ("wight", "Undead"), ("revenant", "Undead"), ("spectre", "Undead"), ("wraith", "Undead"),
    ("banshee", "Undead"), ("mummy", "Undead"), ("ankou", "Undead"),
    ("tzhaar", "Demon"), ("demon", "Demon"), ("dragon", "Demon"), ("devil", "Demon"),
    ("imp", "Demon"), ("abyssal", "Demon"), ("wyvern", "Demon"),
    ("gnome", "Gnome"),
    ("goblin", "Goblin"), ("hobgoblin", "Goblin"),
    ("dwarf", "Dwarf"), ("dwarves", "Dwarf"),
    ("elves", "Elf"), ("elf", "Elf"),
    ("troll", "Troll"), ("ogre", "Troll"), ("cyclop", "Troll"), ("giant", "Troll"),
    ("wizard", "Wizard"), ("sorcerer", "Wizard"),
    ("human", "Human"),
]

DEFAULT_OUT = os.path.join("src", "main", "resources", "npc-voices.json")
DEFAULT_OVERRIDES = os.path.join("tools", "overrides.json")
DEFAULT_PROFILES = os.path.join("tools", "profiles.json")
# Full NPC id -> name dump, used only to cross-reference ids the wiki pages do not
# list (variants) onto wiki data by name. The wiki remains the source of truth.
DEFAULT_SUMMARY_URL = (
    "https://raw.githubusercontent.com/0xNeffarion/osrsreboxed-db/master/docs/npcs-summary.json"
)

VALID_RACES = {"Human", "Elf", "Dwarf", "Goblin", "Gnome", "Troll", "Undead", "Demon", "Wizard"}
VALID_GENDERS = {"Male", "Female"}
PROFILE_FIELDS = {"name", "accent", "style", "pace"}

# Wiki race text -> the eight voice buckets (VoiceProfile). Buckets are
# voice-categorical, not lore-accurate: lore-distinct creatures map to the
# closest available voice (gnome -> Goblin, ogre/cyclops -> Troll, vampyre ->
# Undead, dragon/TzHaar -> Demon). Checked in order, first hit wins.
RACE_BUCKET_RULES = [
    (r"vampyre|vampire|\bvyre\b|zombie|skeleton|ghost|ghoul|undead|wight|shade|"
     r"revenant|mummy|banshee|spectre|wraith|ankou|lich|reanimat", "Undead"),
    (r"demon|devil|\bimp\b|abyssal|dragon|wyvern|wyrm|drake|tzhaar|tztok|tzkal", "Demon"),
    (r"gnome", "Gnome"),
    (r"goblin|hobgoblin", "Goblin"),
    (r"dwarf|dwarven", "Dwarf"),
    (r"\belf\b|\belves\b|elven|gnome elf", "Elf"),
    (r"troll|\bgiant\b|cyclops|ogre|\bent\b|\bgolem\b|\bhuman.*giant", "Troll"),
    (r"\bhuman\b|\bman\b|\bwoman\b|\bgnome child\b", "Human"),
]
RACE_BUCKET_RULES = [(re.compile(p, re.IGNORECASE), b) for p, b in RACE_BUCKET_RULES]

# Sophanem/Menaphos are split out of the Desert league region into the Menaphite
# (Egyptian) ethnicity by matching the NPC's location text.
MENAPHITE_HINT = re.compile(r"sophanem|menaphos|menaphite|necropolis", re.IGNORECASE)

# Single wiki leagueRegion -> ethnicity accent key in tools/profiles.json byEthnicity.
# leagueRegion (where an NPC is found) is the default proxy for ethnicity (where they
# are from); a foreigner is corrected in overrides.json. "Desert" is split into
# kharidian/menaphite by location. Values not listed here (No, General, N/A, ...) and
# any NPC documented across several regions (comma- or &-separated) carry no single
# ethnicity and keep the British default.
SINGLE_ETHNICITY = {
    "misthalin": "misthalin",
    "asgarnia": "asgarnia",
    "kandarin": "kandarin",
    "kourend": "kourend",
    "wilderness": "wilderness",
    "tirannwn": "tirannwn",
    "varlamore": "varlamore",
    "karamja": "karamja",
    "morytania": "morytania",
    "fremennik": "fremennik",
}


def ethnicity_key(league_region, location, categories=None):
    if not league_region:
        return None
    lr = league_region.strip()
    if "," in lr or "&" in lr:
        return None  # documented in several regions -> no single ethnicity
    key = lr.lower()
    if key == "desert":
        # Split the Egyptian Menaphite cities (Sophanem/Menaphos) out of the desert, by the NPC's
        # location text or its wiki categories (e.g. Category:Menaphites, Category:Sophanem).
        hint = " ".join([location or ""] + (categories or []))
        return "menaphite" if MENAPHITE_HINT.search(hint) else "kharidian"
    return SINGLE_ETHNICITY.get(key)


def bucket_for_race(race_text):
    if not race_text:
        return None  # no infobox race field; caller falls back to categories
    for regex, bucket in RACE_BUCKET_RULES:
        if regex.search(race_text):
            return bucket
    return "Human"


def normalise_gender(gender_text):
    if gender_text:
        g = gender_text.strip().lower()
        if g.startswith("f"):
            return "Female"
        if g.startswith("m"):
            return "Male"
    return "Male"


def api_get(params):
    params = dict(params)
    params["format"] = "json"
    params["formatversion"] = "2"
    url = WIKI_API + "?" + urllib.parse.urlencode(params)
    for attempt in range(5):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(req, timeout=60) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except Exception as exc:  # noqa: BLE001 - tooling, retry then surface
            wait = 2 * (attempt + 1)
            print(f"  (api retry {attempt + 1} after {exc}; sleeping {wait}s)", file=sys.stderr)
            time.sleep(wait)
    raise RuntimeError(f"wiki API failed after retries: {url}")


def enumerate_npc_pages(limit=None):
    """All main-namespace page titles transcluding an NPC or Monster infobox, deduped."""
    seen = set()
    titles = []
    for template in INFOBOX_TEMPLATES:
        cont = None
        while True:
            params = {
                "action": "query",
                "list": "embeddedin",
                "eititle": template,
                "einamespace": "0",
                "eilimit": "500",
                "eifilterredir": "nonredirects",
            }
            if cont:
                params["eicontinue"] = cont
            data = api_get(params)
            for entry in data.get("query", {}).get("embeddedin", []):
                title = entry["title"]
                if title not in seen:
                    seen.add(title)
                    titles.append(title)
            if limit and len(titles) >= limit:
                return titles[:limit]
            cont = data.get("continue", {}).get("eicontinue")
            if not cont:
                break
            print(f"  enumerated {len(titles)} pages ...", file=sys.stderr)
    return titles


def bucket_from_categories(categories):
    """Voice bucket inferred from a page's wiki categories, or None. Lets Infobox Monster NPCs
    (which carry no race field) still get a race, e.g. a page in Category:Trolls -> Troll."""
    joined = " ".join(categories).lower()
    for needle, bucket in CATEGORY_RACE_RULES:
        if needle in joined:
            return bucket
    return None


FIELD_RE = {
    "race": re.compile(r"\|\s*race\d*\s*=\s*([^\n|]+)", re.IGNORECASE),
    "gender": re.compile(r"\|\s*gender\d*\s*=\s*([^\n|]+)", re.IGNORECASE),
    "leagueRegion": re.compile(r"\|\s*leagueRegion\s*=\s*([^\n|]+)", re.IGNORECASE),
    "location": re.compile(r"\|\s*location\s*=\s*([^\n|]+)", re.IGNORECASE),
}
ID_RE = re.compile(r"\|\s*id\d*\s*=\s*([^\n|]+)", re.IGNORECASE)


def clean_value(value):
    # Strip wiki markup, refs and templates so "[[Human]]" -> "Human".
    value = re.sub(r"<ref[^>]*>.*?</ref>", "", value, flags=re.IGNORECASE | re.DOTALL)
    value = re.sub(r"<[^>]+>", "", value)
    value = value.replace("[[", "").replace("]]", "")
    value = re.sub(r"\{\{[^}]*\}\}", "", value)
    return value.strip()


def first_field(wikitext, key):
    m = FIELD_RE[key].search(wikitext)
    return clean_value(m.group(1)) if m else None


GENDER_LINE_RE = re.compile(r"\|\s*gender\d*\s*=\s*([^\n|]+)", re.IGNORECASE)


def parse_id_groups(wikitext):
    """One group of ids per |idN= line, preserving order. A switch-infobox page lists ids and
    genders as parallel per-version lines, so the i-th id group pairs with the i-th gender."""
    groups = []
    for raw in ID_RE.findall(wikitext):
        ids = [int(t) for t in re.split(r"[,\s]+", clean_value(raw)) if t.isdigit()]
        if ids:
            groups.append(ids)
    return groups


def parse_genders(wikitext):
    return [normalise_gender(clean_value(g)) for g in GENDER_LINE_RE.findall(wikitext)]


def fetch_infoboxes(titles, batch=30):
    """Yield (title, lead-wikitext, [category titles]) for each page, in batches."""
    for i in range(0, len(titles), batch):
        chunk = titles[i:i + batch]
        data = api_get({
            "action": "query",
            "prop": "revisions|categories",
            "rvprop": "content",
            "rvslots": "main",
            "rvsection": "0",
            "cllimit": "500",
            "titles": "|".join(chunk),
        })
        for page in data.get("query", {}).get("pages", []):
            revs = page.get("revisions")
            if not revs:
                continue
            content = revs[0].get("slots", {}).get("main", {}).get("content", "")
            cats = [c.get("title", "") for c in page.get("categories", [])]
            yield page.get("title", ""), content, cats
        print(f"  parsed {min(i + batch, len(titles))}/{len(titles)} pages ...", file=sys.stderr)
        time.sleep(0.2)


def normalize_name(name):
    """Lower-cased, disambiguation-stripped name for cross-referencing the id dump."""
    if not name:
        return ""
    name = re.sub(r"\(.*?\)", "", name)
    return re.sub(r"\s+", " ", name).strip().lower()


def iter_summary(summary):
    """Yield (npc_id:int, name:str) from a full id -> name NPC dump."""
    records = summary.values() if isinstance(summary, dict) else summary
    for entry in records:
        if not isinstance(entry, dict):
            continue
        try:
            npc_id = int(entry.get("id"))
        except (TypeError, ValueError):
            continue
        name = entry.get("name") or ""
        if name and name.lower() != "null":
            yield npc_id, name


def fetch_json_url(url):
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=120) as resp:
        return json.loads(resp.read().decode("utf-8"))


def build_table_from_wiki(limit=None):
    """Build the id -> {race, gender, region} table and a name -> entry map from the wiki."""
    titles = enumerate_npc_pages(limit=limit)
    print(f"Enumerated {len(titles)} NPC pages from the wiki", file=sys.stderr)
    table = {}
    name_map = {}
    pages_with_ids = 0
    for title, wikitext, categories in fetch_infoboxes(titles):
        groups = parse_id_groups(wikitext)
        genders = parse_genders(wikitext)
        # Infobox race when present (NPC pages); otherwise the page's categories (Monster pages).
        race = bucket_for_race(first_field(wikitext, "race"))
        if race is None:
            race = bucket_from_categories(categories)
        race = race or "Human"
        ethnicity = ethnicity_key(
            first_field(wikitext, "leagueRegion"), first_field(wikitext, "location"), categories)

        def build_entry(gender):
            entry = {"race": race, "gender": gender}
            if ethnicity:
                entry["ethnicity"] = ethnicity
            return entry

        # Gender can vary per version (e.g. male/female guard variants). When the page lists one
        # gender per id group, pair them; otherwise fall back to the first gender for every id.
        aligned = len(genders) == len(groups) and groups
        default_gender = genders[0] if genders else "Male"

        # First page to claim a name wins, so the canonical NPC page beats a stray transclusion.
        key = normalize_name(title)
        if key and key not in name_map:
            name_map[key] = build_entry(default_gender)

        if groups:
            pages_with_ids += 1
            for index, group in enumerate(groups):
                entry = build_entry(genders[index] if aligned else default_gender)
                for npc_id in group:
                    table.setdefault(npc_id, entry)
    return table, len(titles), pages_with_ids, name_map


def fill_from_summary(table, name_map, summary):
    """Cover ids the wiki pages don't list by matching a full id -> name dump to the wiki
    data by name. Catches variant ids whose name still resolves to a documented NPC."""
    filled = 0
    for npc_id, name in iter_summary(summary):
        if npc_id in table:
            continue
        entry = name_map.get(normalize_name(name))
        if entry:
            table[npc_id] = entry
            filled += 1
    return filled


def apply_overrides(table, overrides):
    override_npcs = overrides.get("npcs", {})
    for key, entry in override_npcs.items():
        npc_id = int(key)
        race = entry["race"]
        gender = entry["gender"]
        if race not in VALID_RACES:
            raise ValueError(f"Override {key} has invalid race '{race}'")
        if gender not in VALID_GENDERS:
            raise ValueError(f"Override {key} has invalid gender '{gender}'")
        merged = {"race": race, "gender": gender}
        ethnicity = entry.get("ethnicity")
        if ethnicity:
            merged["ethnicity"] = ethnicity
        table[npc_id] = merged
    return len(override_npcs)


def validate_profiles(profiles):
    if not isinstance(profiles, dict):
        raise ValueError("profiles.json must be a JSON object")
    default = profiles.get("default")
    if not isinstance(default, dict) or not PROFILE_FIELDS.issubset(default):
        raise ValueError(f"profiles.default must be complete with {sorted(PROFILE_FIELDS)}")
    for entry in (profiles.get("byCategory") or []):
        if not isinstance(entry, dict) or not entry.get("keywords"):
            raise ValueError(f"byCategory entry missing keywords: {entry!r}")
    for key in (profiles.get("byId") or {}):
        if key.startswith("_"):
            continue
        try:
            int(key)
        except (TypeError, ValueError):
            raise ValueError(f"byId key '{key}' is not a numeric NPC id")
    return profiles


def load_json(path):
    with open(path, "r", encoding="utf-8") as fh:
        return json.load(fh)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--overrides", default=DEFAULT_OVERRIDES)
    parser.add_argument("--profiles", default=DEFAULT_PROFILES)
    parser.add_argument("--summary", default=DEFAULT_SUMMARY_URL,
                        help="Full id->name NPC dump (URL) for name cross-reference; '' to skip.")
    parser.add_argument("--out", default=DEFAULT_OUT)
    parser.add_argument("--limit", type=int, default=None,
                        help="Cap the number of NPC pages (for quick test runs).")
    args = parser.parse_args()

    profiles = validate_profiles(load_json(args.profiles))
    overrides = load_json(args.overrides)

    table, page_count, pages_with_ids, name_map = build_table_from_wiki(limit=args.limit)
    name_matched = 0
    if args.summary:
        try:
            summary = fetch_json_url(args.summary)
            name_matched = fill_from_summary(table, name_map, summary)
            print(f"  name cross-ref covered {name_matched} extra ids from the id dump",
                  file=sys.stderr)
        except Exception as exc:  # noqa: BLE001 - tooling, surface and continue
            print(f"  (skipping name cross-ref: {exc})", file=sys.stderr)
    override_count = apply_overrides(table, overrides)

    npcs = {str(npc_id): table[npc_id] for npc_id in sorted(table)}

    race_counts, gender_counts, ethnicity_counts = {}, {}, {}
    for v in table.values():
        race_counts[v["race"]] = race_counts.get(v["race"], 0) + 1
        gender_counts[v["gender"]] = gender_counts.get(v["gender"], 0) + 1
        if "ethnicity" in v:
            ethnicity_counts[v["ethnicity"]] = ethnicity_counts.get(v["ethnicity"], 0) + 1

    out = {
        "_meta": {
            "description": "Static precomputed npcId -> {race, gender, region?} lookup plus "
                           "cloud voice profiles, baked into the plugin. Generated offline by "
                           "tools/generate_npc_voices.py from the Old School RuneScape Wiki "
                           "(Infobox NPC). Do not hand-edit; edit tools/overrides.json or "
                           "tools/profiles.json and regenerate.",
            "schema": "npcs[id] = { race, gender, ethnicity? }",
            "source": "oldschool.runescape.wiki Infobox NPC (race/gender/leagueRegion/location), "
                      "cross-referenced by name against a full id dump for variant ids, "
                      "+ curated tools/overrides.json; profiles from tools/profiles.json.",
            "npc_pages": page_count,
            "count": len(npcs),
            "name_matched_ids": name_matched,
            "overrides_applied": override_count,
            "race_counts": dict(sorted(race_counts.items())),
            "gender_counts": dict(sorted(gender_counts.items())),
            "ethnicity_counts": dict(sorted(ethnicity_counts.items())),
            "profiles_bespoke": len(
                [k for k in (profiles.get("byId") or {}) if not k.startswith("_")]),
        },
        "profiles": profiles,
        "npcs": npcs,
    }

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as fh:
        json.dump(out, fh, indent=2, ensure_ascii=False)
        fh.write("\n")

    print(f"Wrote {len(npcs)} NPC entries from {pages_with_ids} pages to {args.out}", file=sys.stderr)
    print(f"  races:   {out['_meta']['race_counts']}", file=sys.stderr)
    print(f"  genders: {out['_meta']['gender_counts']}", file=sys.stderr)
    print(f"  ethnicities: {out['_meta']['ethnicity_counts']}", file=sys.stderr)


if __name__ == "__main__":
    main()
