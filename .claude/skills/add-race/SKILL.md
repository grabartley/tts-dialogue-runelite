---
name: add-race
description: Add a brand-new first-class race (e.g. Tortugan, Monkey) to the plugin, wiring it through the Kokoro and cloud voice maps and the race->accent model, then assign every NPC of that race in overrides. Use when a species needs its own race + dedicated accent, not just a per-NPC or ethnicity tweak.
---

# Add a first-class race

Use this when a species should become a **first-class `NPCRace`** with its own dedicated voice on both backends and its own racial accent, the way Elf, Dwarf, Goblin, Monkey, Gorilla and Tortugan are. This is bigger than [[add-npc-profile]] (which only edits race/gender/ethnicity within the existing race set) and bigger than a region batch ([[fill-npc-profiles-batch]]). When you only need to make one NPC sound right, or push an accent via an existing ethnicity, use [[add-npc-profile]] instead.

## Inputs

- **Race name** (TitleCase, e.g. `Tortugan`). Becomes the enum constant, the `VALID_RACES` member, the `byRace` key, and the override `race` value. Keep it identical everywhere (the generator validates overrides against `VALID_RACES`).
- **Accent** the race should carry everywhere, phrased positively (see World rules).
- **Optional reference material**: a wiki race page, a roster, cache prefixes. Used to find every NPC id of the race.

## Resolution model (why each edit exists)

Cloud profile resolution combines layers: `default -> byRace[race] -> byEthnicity[ethnicity] -> byCategory keyword matches -> byId[id]`. A distinctive race's accent lives in `byRace`, so it **wins over region** (`byEthnicity`): the race keeps its accent wherever its members are found. Do **not** set `ethnicity` on these NPCs (it is a no-op for a distinctive race). Voice timbre (Kokoro speaker id, Gemini sub-pool) is separate from accent and is keyed purely on race+gender.

## The wiring points (touch every one)

1. **`src/main/java/com/grahambartley/VoiceManager.java`**
   - Add the constant to the `NPCRace` enum, just before `UNKNOWN`.
   - Add `<RACE>_MALE` and `<RACE>_FEMALE` to the `VoiceProfile` enum (Kokoro speaker ids + names, see Kokoro rule). Remember to flip the previous last entry's trailing `;` to `,`.
   - Add a `case <RACE>:` to `getVoiceForRaceAndGender(...)` returning female/male profile.
   - Add an `else if (raceLower.contains("..."))` arm to `convertToNPCRace(...)` so a raw wiki/learned race string still maps (the runtime auto-learn path uses this, not just the generator).
2. **`engine-kokoro/src/main/java/com/grahambartley/engine/SpeakerMatrix.java`** — the standalone engine duplicates the speaker matrix. Add the two `private static final int <RACE>_MALE/FEMALE` ids (same ids as VoiceProfile) and a `case "<RACE>":` in `speakerId(...)`. `SpeakerMatrixVoiceProfileDriftTest` fails if these drift from VoiceProfile, so they must match exactly.
3. **`src/main/java/com/grahambartley/synthesis/GeminiVoiceMap.java`** — add a `put(NPCRace.<RACE>, male(...), female(...))` in the constructor (see Gemini rule).
4. **`tools/generate_npc_voices.py`** — add the race to `VALID_RACES`; add a `RACE_BUCKET_RULES` regex (ordered so it does not collide with a broader bucket); add a `CATEGORY_RACE_RULES` entry so Infobox-Monster pages (no race field) still bucket by category.
5. **`tools/profiles.json`** — add a `byRace["<RACE>"]` entry: `name`, `accent`, `style`, `pace`. This is where the racial accent lives.
6. **`tools/overrides.json`** — add the race to the `_comment` valid-race list, then one one-line `npcs[id]` entry per NPC id (race + gender, no ethnicity).
7. **Docs** — `README.md` Voices table row + the "spans N races" count; `docs/npc-voice-tooling.md` race note; the valid-race list in `.claude/skills/add-npc-profile/SKILL.md`.

## Kokoro speaker id rule

The local Kokoro bank `kokoro-multi-lang-v1_0` exposes English voices at ids 0-27, named **alphabetically**:

```
0 af_alloy   1 af_aoede   2 af_bella   3 af_heart   4 af_jessica  5 af_kore
6 af_nicole  7 af_nova    8 af_river   9 af_sarah  10 af_sky     11 am_adam
12 am_echo  13 am_eric   14 am_fenrir 15 am_liam   16 am_michael 17 am_onyx
18 am_puck  19 am_santa  20 bf_alice  21 bf_emma   22 bf_isabella 23 bf_lily
24 bm_daniel 25 bm_fable 26 bm_george 27 bm_lewis
```

Pick two **unused** ids: one `am_`/`bm_` for male, one `af_`/`bf_` for female, with a timbre that fits the race. Each speaker id must be unique across the whole `VoiceProfile` enum (`VoiceManagerTest.everyCategoryMapsToADistinctSpeaker` asserts the count, so bump that number by 2). To find free ids: list the ids already used in `VoiceProfile` and take the gaps. Warmer/relaxed races lean American (`am_`/`af_`); refined races lean British (`bm_`/`bf_`).

## Gemini (cloud) sub-pool rule

Each race anchors to a small, **gender-correct** sub-pool of two Gemini voices per gender, chosen for timbre (gravelly/firm = big imposing; bright/light = small; warm/clear = friendly). `GeminiVoiceMapTest` enforces a hard invariant: **no voice may appear in any male pool and any female pool** (`maleAndFemaleVoicePoolsAreDisjointAcrossEveryRace`). So:
- Reuse only voices already classified for that gender elsewhere in the map, or introduce a voice whose gender you are sure of (the API carries no gender; it is confirmed by ear).
- Every voice must be in the 30-voice catalog hard-coded in `GeminiVoiceMapTest.GEMINI_VOICE_CATALOG`.
- Add the race to that test's `MAPPED_RACES` array.

## Build the roster (find every id)

The generator/runtime keys on the live `NPCComposition#getId`, and a race's members are scattered across variants and locations. Be exhaustive:

1. **Cache sweep.** Grep the local cache dump for every plausible internal-name prefix:
   `/Users/gbartley/.npm/_npx/*/node_modules/@jayarrowz/mcp-osrs/dist/data/npctypes.txt`
   Format is `id<TAB>internal_name`; the id is the first column (NOT the grep line number, which is off by one). Use `awk -F'\t' '$2 ~ /prefix/ {print $1"\t"$2}'`.
2. **Wiki verify.** Cross-check the race's wiki page ("Known <race>s"), the location navbox, and each NPC's Infobox `id`. **Gotcha:** cache internal names can be role-based and misleading (a Tortugan elder was named `slayer_gryphon_guardian`; a Tortugan gardener was `farming_gardener_calquat_3`). Trust the **wiki Infobox `Race` + `id`** as the source of truth, not the internal name. When the internal name and the wiki disagree, list the id and flag it for in-game Debug Mode verification in the QA checklist.
3. **Include all variants.** Combat/`_vis`/`_locked`/`_1op` variant ids of the same NPC all get the entry. Off-location members count too (verify nothing is missed outside the home region).
4. **Gender.** Take the wiki Infobox gender when stated; when genuinely indeterminate default to **Male** (unknown gender routes to the male sub-pool on both backends, so Male is the consistent safe default). Confirm ambiguous ones via dialogue pronouns in Debug Mode where it matters.
5. **Exclude** non-members that share a prefix (creatures, humans, quest NPCs) explicitly.

## Regenerate + validate

```
cp src/main/resources/npc-voices.json /tmp/before.json
python3 tools/generate_npc_voices.py --base /tmp/before.json   # offline, deterministic, no wiki drift
```
Use `--base` (see [[regenerate-npc-voices]]) for an overrides/profiles-only change: it re-applies overrides + the embedded `byRace` profile onto the existing table with a minimal diff and no network. Then confirm **only** your ids changed (diff each entry against the base) and that `byRace["<RACE>"]` is embedded. A full network run is only needed if you also want the wiki to auto-classify members by their Infobox race.

Then:
```
./gradlew spotlessApply && ./gradlew test spotlessCheck
```
Tests to extend: `GeminiVoiceMapTest` (`MAPPED_RACES`), `VoiceManagerTest` (distinct-speaker count), `NpcProfilesResourceTest` (`everyRaceBucketResolvesToItsOwnLayer` list + a stated-accent assertion). `SpeakerMatrixVoiceProfileDriftTest` covers the engine mapping automatically.

## World rules (non-negotiable prose constraints)

- **Phrase accents positively.** Name the wanted accent ("the lilting Bajan English of Barbados"); never describe by negation and never reference America. Gemini renders British/European accents reliably but foreign accents only partially, so make `style` carry the character so the line still reads well if the accent lands only halfway.
- **Keep all prose timeless.** No rollout/batch/PR/date/"for now" references in code, JSON, or comments.
- **British medieval-fantasy default.** Everything is British unless lore or trope says otherwise.

## QA handoff

In the manual QA checklist, call out: a same-race male and female NPC voice gender-correct on **both** backends; a line logging `source=byRace` with the new accent; and any id whose cache internal name disagreed with the wiki, for direct Debug Mode confirmation. To debug a wrong result, see [[diagnose-npc-voice]].
