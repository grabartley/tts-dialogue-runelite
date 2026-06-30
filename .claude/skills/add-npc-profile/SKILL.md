---
name: add-npc-profile
description: Add or correct an NPC's (or the player's) cloud TTS voice, accent, gender, ethnicity, or personality. Use when asked to make an NPC sound a certain way, fix a wrong voice/accent/gender, give a character a bespoke profile, or add a new race/ethnicity/category accent.
---

# Add / correct an NPC voice profile

The cloud (Gemini) backend voices each line from a bundled lookup,
`src/main/resources/npc-voices.json`, which is **generated** by
`tools/generate_npc_voices.py` from two hand-curated sources plus the OSRS Wiki.
**Never hand-edit `npc-voices.json`.** Edit a source, then regenerate.

## The two sources you edit

| File | Controls | Shape |
|------|----------|-------|
| `tools/overrides.json` | the NPC **classification** (`race`, `gender`, `ethnicity`) per id. Picks the voice timbre on **both** backends and selects which profile layers apply. | `npcs[id] = { race, gender, ethnicity? }` |
| `tools/profiles.json` | the cloud **delivery** prose (`accent`, `style`, `pace`), in layers. | `default`, `player`, `byRace`, `byEthnicity`, `byCategory[]`, `byId{}` |

## How a profile resolves (combining, deepest wins per single-valued field)

```
default -> byRace[race] -> byEthnicity[ethnicity] -> every byCategory keyword match -> byId[npcId]
```

- `style` **accumulates** across all matching layers; `name`, `accent`, `pace` take the **most specific** layer that sets them.
- `byEthnicity` applies **only to plain folk** (Human / unknown race); distinctive races keep their racial accent everywhere.
- `ethnicity` means **where the NPC is from** (defaulted from the wiki `leagueRegion`, i.e. where they are found). Accent follows ethnicity in most cases.

## Decide what to change

1. **Wrong/missing race, gender, or origin accent** (e.g. a woman voiced male, a talkable monster with no race, a foreigner sounding like the locals) -> `tools/overrides.json`.
   - An override **replaces** the table entry, so it must carry `race` + `gender`. `race` must be one of Human, Elf, Dwarf, Goblin, Gnome, Monkey, Gorilla, Troll, Undead, Demon, Wizard, Tortugan; map a wiki race that is not one of these (Dorgeshuun -> Goblin, Vampyre -> Undead, Imp -> Demon, ape -> Gorilla, ...). A truly unmappable species (Serpent, Merfolk, ...) gets a `byId` style only.
   - Set `ethnicity` to a `byEthnicity` key to fix where they're from; **omit** `ethnicity` to clear a wrong one the wiki inferred (a foreigner in Morytania -> drop it -> British default). Ethnicity is a no-op for non-human races (the racial accent wins), so only set it on Human/Unknown NPCs.
   - This is also how you make a non-region accent land via a dedicated ethnicity (e.g. Ak-Haranu -> `ethnicity: easternlands`).
2. **A unique personality / a quirk accent that is not an ethnicity** -> `tools/profiles.json` `byId[id]`. Sparse: usually just `name` + `style`. Only set `accent`/`pace` for a genuine per-character quirk (most origin accents belong in `overrides.json` ethnicity instead, so you do not repeat an accent string across variant ids).
3. **A whole new creature/lore accent** (vampyre, leprechaun, ...) -> add a `byCategory` entry (keywords match the display name, word-bounded, case-insensitive). Creature categories **define** the accent; **social-role** categories (royalty, knight, noble, monk, wizard) are **style-only** so the ethnicity accent shows through.
4. **A new region/ethnicity accent** -> add a `byEthnicity` key and map a `leagueRegion` to it in `tools/generate_npc_voices.py` (`SINGLE_ETHNICITY` / `ethnicity_key`).

## Find the NPC id

The table is keyed by the NPC's **active id** (`NPC#getId`), which for transformed
multiloc NPCs differs from the base composition id. Get it from **Debug Mode**
(it logs `id=` per line), the OSRS Wiki infobox, or the osrs MCP `search_npctypes`.
An NPC can have several variant ids, list them all in overrides.

To check whether an NPC even **speaks** (so it deserves a profile rather than being
a silent mob/critter), the reliable signal is whether the wiki has a
`Transcript:<name>` page (namespace 120), not the infobox `Talk-to` line, which is
blank on many genuine talkers (monster-infobox bosses, cave goblins). See
`fill-npc-profiles-batch` for the batch sweep.

## World rules (keep consistent)

- British medieval fantasy. Commoners speak **common** British; royalty/knights/nobles/high society are **posh** RP, carried by the role's **style register**, not an accent. Distinctive races and lore creatures keep their accents (dwarf Scottish, troll Brixton, gnome country Irish, leprechaun Irish, vampyre Dracula, Fremennik Norse, wizard wise).
- Phrase accents **positively**; never by negation.
- No transient comments (no "for now", batch/PR/date references) in code or JSON.
- Do **not** change pitch on the fly; depth comes from the race -> Gemini voice map (`GeminiVoiceMap`).
- Gemini renders British/European accents reliably; foreign accents (Italian, Egyptian, West African, Japanese) are inconsistent in the model, no prompt guarantees them.

## Apply and verify

In both sources, `byId` and `npcs` entries are **one-line objects** (`"id": { ... }`); keep that shape so the diff stays small.

```bash
python3 tools/generate_npc_voices.py        # regenerates npc-voices.json (needs network)
./gradlew test spotlessCheck
```

Then confirm in-game with the `run-game-client` skill and **Debug Mode** on: the
log line `[TTS profile] npc='...' ... -> '...' (source=..., accent='...')` shows
the resolved profile and which layers won. Commit the regenerated
`npc-voices.json` with the source edits. See `diagnose-npc-voice` to investigate a
wrong voice first, and `regenerate-npc-voices` for the table mechanics.

Whenever the table changes, update the NPC counts in `README.md` to match: the
total entry count (`_meta.count`) and the bespoke-personality count
(`profiles.byId` length) from the regenerated `npc-voices.json`, in the same commit.
