# NPC voice table tooling

Offline tooling that produces the bundled `src/main/resources/npc-voices.json`
lookup table. **None of this runs inside the plugin.** At runtime the plugin only
reads the generated JSON and does in-memory map lookups keyed by NPC id, so there
are no network calls or large downloads when choosing a voice.

## Files

- `tools/generate_npc_voices.py` - the generator. Pulls every NPC's race, gender
  and ethnicity from the Old School RuneScape Wiki, merges the curated
  overrides, embeds the voice profiles, and writes the bundled resource.
- `tools/overrides.json` - hand-curated, **authoritative** `npcId -> {race,
  gender, ethnicity?}` entries. These always win over the wiki, for pinning the rare
  NPC the wiki gets wrong or does not cover.
- `tools/profiles.json` - hand-curated **character voice profiles** for the cloud
  (Gemini) backend (accent, style, pace). Embedded verbatim into the output under
  a top-level `profiles` key. See [Character voice profiles](#character-voice-profiles-cloud).

## Data source

The [Old School RuneScape Wiki](https://oldschool.runescape.wiki) is the
authoritative, current source. Every NPC page transcludes `Template:Infobox NPC`,
which exposes `race`, `gender`, `leagueRegion`, `location` and one or more cache
`id`s. The generator:

1. Enumerates every main-namespace page transcluding `Template:Infobox NPC` (via
   the MediaWiki `embeddedin` API).
2. Fetches each page's lead wikitext in batches and parses every infobox.
3. Maps each cache id to `{race, gender, ethnicity}`.

Because race and gender come straight from the wiki, townsfolk get the correct
gender (e.g. Cecilia is Female) and newly released NPCs (Varlamore, etc.) are
covered as soon as the wiki documents them. The ids are real cache ids the live
client reports.

The wiki does not always list every cache id an NPC uses (variants, multiloc
versions). To close that gap the generator also cross-references a full
`id -> name` NPC dump against the wiki data **by name**, so a variant id whose
name still resolves to a documented NPC is covered too.

> **Coverage notes.** The live client reports a transformed/multiloc NPC's
> *active* id, which can differ from its base composition id; the runtime resolves
> by the active id first (then the base id) to match the wiki. Combat creatures use
> a separate `Infobox Monster` that carries **no race/gender/ethnicity**, so they
> cannot be auto-derived; the handful of *talkable* monsters (e.g. TzHaar-Mej) are
> pinned in `overrides.json`. Anything still unknown (a brand-new NPC) is left to
> the runtime auto-learn fallback.

## Mapping rules

- **Race.** The wiki race text is mapped onto a voice bucket. Buckets are
  voice-categorical, not lore-accurate: ogre/cyclops -> Troll, vampyre -> Undead,
  dragon/TzHaar -> Demon. Gnomes are kept as their own `Gnome` race (so they can
  sound country Irish) even though they share the small/high goblin voice timbre.
- **Gender.** Taken verbatim (`Male`/`Female`); defaults to `Male` only when the
  wiki has none.
- **Ethnicity.** The wiki `leagueRegion` (where the NPC is found) is the default
  proxy for ethnicity (where they are from) and maps to an ethnicity accent key.
  `Desert` splits into `kharidian` (Middle Eastern) and `menaphite` (Egyptian, for
  the Sophanem/Menaphos cities). An NPC documented across several league regions
  has no single ethnicity, so it keeps the British default. Ethnicity is an
  **origin** signal, not where the NPC is standing, so a Varrock guard exploring
  Karamja still sounds Misthalin; a foreigner is corrected in `overrides.json`.

## Regenerate the table

From the repo root (needs network access to the wiki):

```bash
python3 tools/generate_npc_voices.py
# or a quick partial run for testing:
python3 tools/generate_npc_voices.py --limit 500
```

Then build and test:

```bash
./gradlew test spotlessCheck
```

Commit the regenerated `src/main/resources/npc-voices.json` alongside any
overrides or profile changes.

## Fixing a wrong voice

**Do not hand-edit `npc-voices.json`** (it gets overwritten on regeneration).
First, fix it at the source: the wiki itself, if its infobox is wrong. For a
local-only correction, or to pin a talkable monster the wiki splits into
`Infobox Monster`, add the entry to `overrides.json` and regenerate:

```json
{
  "npcs": {
    "2154": { "name": "TzHaar-Mej", "race": "Demon", "gender": "Male" }
  }
}
```

The optional `name` field is documentation only. `ethnicity` is also optional (set a byEthnicity key, or omit to clear a wrong one). Find
an NPC's id with the RuneLite developer tools, the wiki, or **Debug Logging** in the
plugin (it logs the id and chosen voice/profile per line).

## Character voice profiles (cloud)

Alongside the `npcId -> {race, gender, ethnicity}` table, the bundled resource
carries a `profiles` section that steers **how** the cloud (Gemini) backend
delivers each line: accent, style, and pace, rendered into a Gemini `AUDIO
PROFILE` / `DIRECTOR'S NOTES` block prepended to the spoken text. Chat-head
emotion is layered on top as a separate inline tag, so the two compose. The local
Kokoro backend ignores profiles (it takes no prompt).

The source of truth is `tools/profiles.json`; the generator embeds it under the
output's `profiles` key. This is a **British** medieval fantasy world: commoners
speak plain, common British, only royalty, knights, nobles and other high society
use posh Received Pronunciation.

### Layers (all matches combine)

An NPC can be several things at once (a Fremennik human, a ghost pirate), so
**every** matching layer contributes. `style` accumulates across all contributing
layers so the persona blends; `name`, `accent`, and `pace` are single-valued, so
the most specific layer that sets each one wins.

1. `default` - the global British fallback. **Must be complete** (all four of
   `name`, `accent`, `style`, `pace`). Every other layer is sparse.
2. `byRace[race]` - one per race bucket.
3. `byEthnicity[ethnicity]` - an ethnicity accent. Applied **only to the plain folk**
   (Human / unknown race) so distinctive races keep their racial accent wherever
   they are. Every league region has an accent: the far lands follow the
   real-world cultures they are based on (Desert -> Middle Eastern,
   Sophanem/Menaphos -> Egyptian, Karamja -> West African, Fremennik -> Norse,
   Morytania -> Eastern European gothic, Varlamore -> Mediterranean), the central
   kingdoms use distinct English regional accents (Misthalin, Asgarnia West
   Country, Kandarin Yorkshire, Kourend, Wilderness), and Tirannwn is Welsh.
4. `byCategory[]` - an ordered list; **every** entry whose `keywords` word-match
   the display name contributes. This expresses categories the race buckets cannot
   (leprechaun -> Irish, vampyre -> Dracula-esque, gnome, imp, ghost, pirate,
   royalty, knight, noble, wizard, ...). Matching is case-insensitive and bounded
   on word edges, so `imp` matches "Imp" but not "important".
5. `byId[npcId]` - per-NPC **bespoke** overrides keyed by the live NPC id. Sparse:
   carry only what is unique to the character (usually `name` + `style`); its
   style is added on top of the blend, and accent and pace inherit unless it sets
   them. This is the highest-precedence layer, so it can pin any character's
   delivery regardless of ethnicity.

Player lines use the `player` layer over the default; the three player fields in
the plugin config (accent/style/pace) override it at runtime when non-blank.
