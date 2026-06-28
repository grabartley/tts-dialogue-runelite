---
name: fill-npc-profiles-batch
description: Bulk-author bespoke per-NPC voice profiles (and race/gender/ethnicity corrections) for a whole league region, researching each from the OSRS Wiki and osrs MCP. Use for the region-by-region rollout (umbrella issue #122; batches #123-125...) or when asked to "fill in NPC profiles" for an area.
---

# Fill in NPC profiles for a league region

Bulk-add bespoke `byId` profiles and corrections for every talkable NPC in a
**league region** at a time, themed by region to avoid overlap. The engine and
per-NPC editing are covered by `add-npc-profile`; this skill is the **batch
research + authoring** loop. Work in a fresh worktree (see the `worktree` skill)
tied to the batch issue, and do every edit, regen, and commit **inside that
worktree** (verify `pwd` and `git branch --show-current`; the shell can land you
in the primary checkout on `main`).

There is **no fixed NPC cap**. Cover the whole region. Splitting a region only
makes sense if a reviewer asks for it.

## 1. Build the candidate list: league region UNION location, deduped

Select on **two** signals and take the **union, deduped by id**, so nothing
between or under the towns is missed:

1. **By `leagueRegion`** equal to the region (single-region only).
2. **By `location`** naming a place inside the region. Some NPCs have a blank or
   multi-region `leagueRegion` but a `location` that is squarely in the region
   (an underground city, a guild, a mine, an island). Keep a list of the region's
   place-names (its towns plus its named sub-areas) and substring-match the wiki
   `location` field against them; include any hit even if its `leagueRegion`
   did not qualify.

Do **not** rely on `leagueRegion` alone, and do **not** filter by town as the
*only* axis either. Union both. The generator already enumerates and parses every
NPC page; reuse its functions:

```python
import sys; sys.path.insert(0, "tools"); import generate_npc_voices as g
REGION = {"misthalin", "asgarnia"}                 # <- your league region(s)
PLACES = ["lumbridge", "varrock", "draynor", "edgeville", "falador",
          "burthorpe", "taverley", "port sarim", "dorgesh", "grand exchange",
          "dwarven mine", "barbarian village", "wizards' tower", "entrana", ...]
titles = g.enumerate_npc_pages()                   # ~5-6k pages, slow; cache it
for title, wikitext, cats in g.fetch_infoboxes(titles):
    lr = (g.first_field(wikitext, "leagueRegion") or "").lower()
    loc = (g.first_field(wikitext, "location") or "").lower()
    single = lr and "," not in lr and "&" not in lr   # multi-region -> British default
    in_region = (single and lr in REGION) or any(p in loc for p in PLACES)
    if not in_region:
        continue
    ids = sorted({i for grp in g.parse_id_groups(wikitext) for i in grp})
```

Then:
- **Talkable filter:** keep NPCs whose `|options...=` line contains `Talk-to`
  (case-insensitive). This drops museum displays, livestock, pets, furniture, and
  fake-player NPCs. **Caveat:** some NPCs that genuinely talk in-game have a blank
  options line or only an `Infobox Monster` page (combat-flavoured NPCs with minor
  dialogue, e.g. the Dorgesh-Kaan cave goblin miners/guards). The wiki
  under-documents them, so the Talk-to filter and even the location union can miss
  them. The **race-sanity sweep** and the **in-game QA pass** (sections 4 and 6)
  are the safety nets for that class; do not assume the wiki is complete.
- **Subtract the already-bespoke:** skip any NPC whose ids are already in
  `tools/profiles.json` `byId`.
- **Cache** the enumeration/fetch to a temp file. The full wiki crawl takes
  several minutes; you will re-run analysis many times.

## 2. Research in parallel (the fan-out)

Authoritative data is per-page, so fan the research out across **subagents** (the
Agent tool, `general-purpose`), ~15 NPCs each, run concurrently
(`run_in_background: true`). Key results by **exact NPC title (name)**, never by
id, so you control id mapping locally (one NPC can have many cache ids). Write a
shared instructions file and point each agent at its chunk file + an output file.
Per NPC the agent returns: `name`, `style` (<=200 chars, delivery only),
`race`, `gender`, `race_corrected`/`gender_corrected` flags, `ethnicity_origin`,
optional `accent`/`pace`, and a confidence.

**Origin is the part to get right.** `ethnicity_origin` is where the character is
truly FROM, as a `byEthnicity` key, or `none` if no regional accent fits. The
common case is a local (origin == the found region), but the whole point of the
region-wide pass is catching the **foreigners**: visitors, immigrants, quest
travellers. Tell the agents explicitly NOT to default everyone to the found
region. Watch especially for hubs that gather people from across Gielinor: the
**Grand Exchange specialist clerks** are recruited from different kingdoms (Farid
Morrisane is Kharidian, Relobo Blinyo is Karamjan), barbarians are Fremennik,
desert/eastern traders carry their own accents, and so on.

## 3. Author the edits (the merge)

Merge the result files locally, then write into the two source files. **Never
hand-edit `src/main/resources/npc-voices.json`** (it is generated).

- **Bespoke personality** -> `tools/profiles.json` `byId`: sparse, `name` +
  `style` (delivery/temperament, no quest lore, no emotion words). Set
  `accent`/`pace` only for a real per-character quirk. **Attach the profile to
  every one of the NPC's cache ids** so variants resolve identically.
- **Origin / accent** -> `tools/overrides.json` `ethnicity`, NOT a repeated
  `byId` accent. An override **replaces** the whole table entry, so it must carry
  `race` + `gender` too. Set `ethnicity` to the true `byEthnicity` key to give a
  foreigner their accent; **omit** `ethnicity` to clear a wrong one (foreigner
  from a region with no accent key) so they fall back to the British default.
- **Race / gender corrections** -> `tools/overrides.json` (mis-gendered NPCs,
  talkable monsters with no wiki race, switch-infobox gaps).

Decide an override only by diffing the researched value against what the
generator would produce (`g.bucket_for_race(rawRace)`, `g.normalise_gender`,
ethnicity defaulted from the found region). Add an override only when something
differs; most locals need none.

## 4. Gotchas (learned the hard way)

- **Ethnicity applies only to plain folk.** The resolver
  (`NpcProfileTable.resolveNpc`) layers `byEthnicity` only when race is
  `Human`/`Unknown`. For a Dwarf/Goblin/Gnome/Troll/etc. the ethnicity field is a
  **no-op**, the racial accent always wins. Do not bother setting/clearing
  ethnicity on non-human NPCs; just fix their race.
- **Normalise non-standard wiki races.** Race must be one of Human, Elf, Dwarf,
  Goblin, Gnome, Troll, Undead, Demon, Wizard. The generator buckets most
  substrings ("Imcando dwarf" -> Dwarf, "goblin (race)" -> Goblin), but odd ones
  fall through to Human: map them in the merge (`Dorgeshuun`/`Cave goblin` ->
  Goblin, `Vampyre`/`Ghost` -> Undead, `Imp`/`Nihil`/`Hellhound` -> Demon,
  `Fairy`/`Dryad` -> Elf, `Ork`/`Giant` -> Troll). Truly unmappable races
  (Serpent, Dragonkin, Merfolk, Centaur) get a `byId` **style only**, no override.
- **Skip the non-voiced.** Cats (catspeak meows would get a silly human voice),
  boss pets, and overhead-only critters survive the Talk-to filter sometimes;
  drop them.
- **Race-sanity sweep (catches wiki-invisible NPCs).** Some talkable NPCs never
  appear on a usable wiki page at all (no `leagueRegion`, no `location`, no
  `Talk-to`); they reach the bundled table only via the id-dump name cross-ref,
  defaulted to `Human`. The Dorgesh-Kaan cave goblin miners/guards (ids 5330-5339,
  internal names `cave_goblin_*`) were exactly this: voiced as humans. Catch them
  by cross-checking the **cache internal names** (osrs MCP `search_npctypes`, or
  the local `npctypes` dump) against the table race: an internal name containing a
  race keyword (`goblin`, `dwarf`, `gnome`, `troll`, `ghost`, `skeleton`, `imp`,
  `demon`, `dragon`, ...) whose table race is `Human` is almost always a miss. Add
  `overrides.json` race corrections for the hits. Run this sweep for the region's
  signature races before shipping.
- **File formatting is hand-curated.** `profiles.json` `byId` and
  `overrides.json` `npcs` use **one-line entry objects**; other layers are
  multi-line. A blind `json.dump(indent=2)` reformats every existing entry and
  produces a giant noisy diff. **Insert append-only** (text insertion before the
  container's closing brace, one line per entry, comma the previous last line),
  never reorder existing entries.
- **Regenerate matches source order.** After editing the source files, rerun the
  generator so `npc-voices.json` reflects them; the embedded `byId` is verbatim.
- **Multi-region NPCs** (wiki `leagueRegion` listing several, comma/`&`
  separated) carry no single ethnicity and keep the British default. They are out
  of scope for a single-region batch by design, not "missed".

## 5. World rules

British medieval fantasy: commoners common British; royalty/knights/nobles posh
via **style register**, not a new accent; distinctive races and lore creatures
keep their racial accents (handled by the race layer, do not restate them in a
`byId` accent); `ethnicity` = origin, following the real-world cultures the OSRS
locations are based on. Phrase accents positively, never mention America. No
transient comments. Gemini renders British/European accents reliably, foreign
ones inconsistently, do not over-invest where the model cannot deliver.

## 6. Regenerate, verify, ship

```bash
python3 tools/generate_npc_voices.py
./gradlew test spotlessCheck
```

Spot-check resolutions with `run-game-client` + Debug Mode (`diagnose-npc-voice`),
checking a local commoner, a flagged foreigner, a corrected non-human, and a
quirk NPC. Then open the region PR linked to the batch issue. See
`add-npc-profile`, `regenerate-npc-voices`, and `create-issue` for the
surrounding flow.
