---
name: regenerate-npc-voices
description: Regenerate the bundled NPC voice + profile table (src/main/resources/npc-voices.json) from the OSRS Wiki. Use when refreshing NPC coverage (newly released NPCs), after editing tools/overrides.json or tools/profiles.json, or after changing the generator's mapping rules.
---

# Regenerate the NPC voice table

`src/main/resources/npc-voices.json` is **generated**, never hand-edited. It holds
`_meta`, the `profiles` section (embedded from `tools/profiles.json`), and the
`npcs` table (`id -> {race, gender, ethnicity?}`).

## Command

```bash
python3 tools/generate_npc_voices.py        # needs network access to the wiki
./gradlew test spotlessCheck
```

Quick partial run for testing: `--limit 500`. Commit the regenerated
`npc-voices.json` alongside any `overrides.json` / `profiles.json` edits.

## What the generator does

1. Enumerates every main-namespace page transcluding `Template:Infobox NPC` **or** `Template:Infobox Monster`.
2. Per page: race from the infobox `race` field, or, for Monster pages that lack it, from the page **categories** (e.g. `Category:Trolls` -> Troll); gender paired **per version** (switch infoboxes list a gender per id group); ethnicity from `leagueRegion` (Desert splits into `kharidian`/`menaphite` via location or `Category:Menaphites`/`Sophanem`).
3. Cross-references a full `id -> name` dump (`--summary`) by name to cover variant ids the wiki pages don't list.
4. Applies `tools/overrides.json` last (authoritative: race/gender/ethnicity).
5. Embeds `tools/profiles.json` under `profiles`.

## Profile architecture (for reference)

- Layers combine: `default -> byRace -> byEthnicity (plain folk only) -> byCategory (every keyword match) -> byId`. `style` accumulates; `name`/`accent`/`pace` take the most specific layer.
- `tools/profiles.json` = delivery prose (accent/style/pace). `tools/overrides.json` = classification (race/gender/ethnicity), which also feeds the offline Local backend's voice.
- British world: commoners common British, royalty/knights/nobles posh via style register; distinctive races and lore creatures keep their accents; ethnicity = origin (corrected in overrides for foreigners); never mention America; no transient comments.

## Notes / known limits

- The full run scrapes a few thousand pages (minutes). Race/gender/ethnicity come from the wiki, so accuracy tracks the wiki.
- Coverage gaps that fall to overrides or the runtime wiki fallback: variant ids in neither the wiki id-lists nor the id dump; talkable monsters whose category gives no race; disambiguation-named NPCs ("Citizen", "Priest") with no page data.
- See `add-npc-profile` to make a change and `diagnose-npc-voice` to investigate one.
