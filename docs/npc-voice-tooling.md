# NPC voice table tooling

Offline tooling that produces the bundled `src/main/resources/npc-voices.json`
lookup table. **None of this runs inside the plugin.** At runtime the plugin only
reads the generated JSON and does a single in-memory map lookup keyed by NPC id,
so there are no network calls or large downloads when choosing a voice.

## Files

- `tools/generate_npc_voices.py` - the generator. Builds the table from a **full
  NPC dump** plus the curated overrides, then writes the bundled resource.
- `tools/overrides.json` - hand-curated, **authoritative** `npcId -> {race, gender}`
  entries. These always win over anything the generator infers, and they pin
  high-traffic named dialogue NPCs (Hans, the Cook, quest givers) whose gender
  cannot be read off the name.

## Data sources

- **Full NPC summary (required).** [`npcs-summary.json`][summary] from the
  community `osrsreboxed-db` fork (the now-defunct OSRSBox DB's successor). This
  is the coverage backbone: it publishes `id -> name` for **every** NPC
  definition, including the peaceful, dialogue NPCs players actually talk to. Its
  ids are the real RuneLite/OSRS cache ids, so they match what the live client
  reports via `NPCComposition#getId` (verified against the OSRS cache, e.g.
  Hans = 3105, Cook = 225, Thurgo = 4733).
- **Monster dump (optional).** OSRSBox `monsters-complete.json` is still consulted
  when reachable, only for the richer `examine` text it carries on attackable
  NPCs, which sharpens race/gender on monsters. The generator runs fine without
  it.

> Earlier versions sourced **only** from `monsters-complete.json` (attackable
> monsters), so peaceful dialogue NPCs were absent and collapsed to the
> human-male default, and the ids did not even match the live client's id space.
> That is the bug this tooling now fixes.

[summary]: https://raw.githubusercontent.com/0xNeffarion/osrsreboxed-db/master/docs/npcs-summary.json

## Regenerate the table

From the repo root:

```bash
# Downloads the full NPC summary (and, if reachable, the monster dump) and
# writes the resource:
python3 tools/generate_npc_voices.py
```

Or point it at local dumps and/or a custom output path:

```bash
python3 tools/generate_npc_voices.py \
    --npcs /path/to/npcs-summary.json \
    --monsters /path/to/monsters-complete.json \
    --overrides tools/overrides.json \
    --out src/main/resources/npc-voices.json
```

Then build and test:

```bash
./gradlew test spotlessCheck
```

Commit the regenerated `src/main/resources/npc-voices.json` alongside any
overrides changes.

## How classification works

1. **Full NPC source.** Every named NPC from `npcs-summary.json` is processed and
   **kept** (including plain Human/Male townsfolk), so the correct gender is
   always pinned. The optional monster dump adds `examine` text for attackable
   NPCs.
2. **Deterministic classifier.** A conservative, word-aware keyword classifier
   assigns a race (Human, Elf, Dwarf, Goblin, Troll, Undead, Demon, Wizard) and
   gender (Male, Female) from the name/examine text:
   - **Race:** distinctive creature keywords map to the closest voice bucket;
     anything human-looking defaults to `Human`.
   - **Gender:** gendered titles/words (e.g. `woman`/`lady`/`sister` vs
     `man`/`sir`/`father`) win first. For human-looking NPCs with no title, a
     small **curated female first-name allowlist** (e.g. Gertrude, Cassie) fires
     on the first name token so common female townsfolk are not defaulted to
     male. Everything else defaults to `Male`.
3. **No dropping.** Unlike the old generator, entries that resolve to the
   Human/Male default are **kept**, so townsfolk get an explicit, correct gender
   instead of falling through to the runtime default.
4. **Merge overrides.** `overrides.json` is applied last and always wins.

## Fixing a wrong voice

The classifier is intentionally simple, so it will get some NPCs wrong. **Do not
hand-edit `npc-voices.json`** (it gets overwritten on regeneration). Instead add
or correct the entry in `overrides.json`, then regenerate. Example:

```json
{
  "npcs": {
    "10681": { "name": "Aubury", "race": "Wizard", "gender": "Male" }
  }
}
```

The optional `name` field is documentation only and is ignored by the generator.
You can find an NPC's id with the RuneLite developer tools, the OSRS Wiki, or by
enabling **Debug Mode** in the plugin (it logs the id and chosen voice per NPC).

## Expanding coverage

The table can be crowdsourced and grown over time. Three ways to add coverage:

- Add authoritative entries to `overrides.json` (best for named dialogue NPCs and
  corrections).
- Add an unambiguous female first name to the curated allowlist in
  `generate_npc_voices.py`, then regenerate (best for whole townsfolk classes).
- Improve the race keyword rules in `generate_npc_voices.py` for whole classes of
  creatures, then regenerate.
