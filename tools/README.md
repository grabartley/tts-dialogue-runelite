# NPC voice table tooling

Offline tooling that produces the bundled `src/main/resources/npc-voices.json`
lookup table. **None of this runs inside the plugin.** At runtime the plugin only
reads the generated JSON and does a single in-memory map lookup keyed by NPC id,
so there are no network calls or large downloads when choosing a voice.

## Files

- `generate_npc_voices.py` - the generator. Builds the table from a static
  OSRSBox monster dump plus the curated overrides, then writes the bundled
  resource.
- `overrides.json` - hand-curated, **authoritative** `npcId → {race, gender}`
  entries. These always win over anything the generator infers, and they cover
  peaceful NPCs (shopkeepers, quest givers) that the monster dump omits.

## Regenerate the table

From the repo root:

```bash
# Downloads the OSRSBox monster dump automatically and writes the resource:
python3 tools/generate_npc_voices.py
```

Or point it at a local dump and/or a custom output path:

```bash
python3 tools/generate_npc_voices.py \
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

1. **Static source.** A static OSRSBox monster dump provides real
   `npcId → name/examine` data (downloaded from the osrsbox-db GitHub mirror by
   default). The dump only covers attackable NPCs.
2. **Deterministic classifier.** A conservative, word-aware keyword classifier
   assigns a race (Human, Elf, Dwarf, Goblin, Troll, Undead, Demon, Wizard) and
   gender (Male, Female) from the name/examine text. Strong signals only.
3. **Drop ambiguous defaults.** Entries with no distinctive signal would resolve
   to the runtime default (Human/Male) anyway, so they are dropped to keep the
   table meaningful and small.
4. **Merge overrides.** `overrides.json` is applied last and always wins.

## Fixing a wrong voice

The classifier is intentionally simple, so it will get some NPCs wrong. **Do not
hand-edit `npc-voices.json`** (it gets overwritten on regeneration). Instead add
or correct the entry in `overrides.json`, then regenerate. Example:

```json
{
  "npcs": {
    "3219": { "name": "Aubury", "race": "Wizard", "gender": "Male" }
  }
}
```

You can find an NPC's id with the RuneLite developer tools, the OSRS Wiki, or by
enabling **Debug Mode** in the plugin (it logs the id and chosen voice per NPC).

## Expanding coverage

The table can be crowdsourced and grown over time. Two ways to add coverage:

- Add authoritative entries to `overrides.json` (best for peaceful NPCs and
  corrections).
- Improve the keyword rules in `generate_npc_voices.py` for whole classes of
  NPCs, then regenerate.
