---
name: fill-npc-profiles-batch
description: Bulk-author bespoke per-NPC voice profiles (and race/gender/ethnicity corrections) for a region or batch of NPCs, researching each from the OSRS Wiki and osrs MCP. Use for the 250-NPC-per-PR rollout (umbrella issue #122; batches #123-125) or when asked to "fill in NPC profiles" for an area.
---

# Fill in NPC profiles for a region / batch

Bulk-add bespoke `byId` profiles and corrections for ~250 NPCs at a time, themed
by region to avoid overlap. The engine and per-NPC editing are covered by
`add-npc-profile`; this skill is the **batch research + authoring** loop. Work in
a fresh worktree (see the `worktree` skill) tied to the batch issue.

## 1. Build the candidate list

Pick a region (e.g. Misthalin, Karamja). Get the NPCs that need bespoke work:

- Query the bundled table for that ethnicity, minus NPCs already in `profiles.json` `byId`:
  ```bash
  python3 - <<'PY'
  import json
  d=json.load(open('src/main/resources/npc-voices.json'))
  done=set(k for k in d['profiles']['byId'] if not k.startswith('_'))
  region='karamja'
  ids=[i for i,v in d['npcs'].items() if v.get('ethnicity')==region and i not in done]
  print(len(ids), ids[:50])
  PY
  ```
- Or enumerate by area from the wiki (pages with the matching `leagueRegion`), or the osrs MCP `search_npctypes` for an area's internal names.
- Prioritise the **most-talked-to** NPCs (quest givers, shopkeepers, hub characters). Cap the batch at ~250; `log` what you skip.

## 2. Research in parallel (the fan-out)

Authoritative data is per-page, so fan the research out across **subagents** (the
Agent tool, `general-purpose`), ~10-15 NPCs each, run concurrently. Give each a
strict contract so results merge with no parsing:

> Research these OSRS NPCs for a TTS voice plugin. We voice existing dialogue, so
> only DELIVERY matters: personality/temperament, and accent/pace ONLY if they
> differ from the British default. Use the osrs MCP (ToolSearch for
> `mcp__osrs__search_npctypes`, `mcp__osrs__osrs_wiki_search`,
> `mcp__osrs__osrs_wiki_parse_page`) and/or the wiki API. For each NPC, verify the
> canonical conversational **id** from the cache; omit any you can't confirm.
> Return ONLY a JSON object `{ "<id>": { "name": ..., "style": "<=200 chars" } }`
> (no markdown). Add `accent`/`pace` only for a genuine per-character quirk.

Batch the wiki directly when you don't need an LLM: `action=query&prop=revisions&
rvprop=content&rvslots=main&rvsection=0&titles=A|B|...` (up to 50 titles) parses
the same `Infobox NPC` fields the generator uses. Launch the subagents
`run_in_background: true` and merge their returned JSON.

## 3. Author the edits

- **Bespoke personality** -> `tools/profiles.json` `byId`: sparse, usually `name` + `style` (delivery/temperament, no quest lore, no emotion words, <~200 chars). Set `accent`/`pace` only for a real quirk.
- **Origin / accent** -> `tools/overrides.json` `ethnicity` (set or clear), NOT a repeated `byId` accent. Foreigners in a region get their true ethnicity (or it cleared).
- **Race / gender corrections** -> `tools/overrides.json` (talkable monsters with no wiki race, mis-gendered NPCs, switch-infobox gaps).
- Verify the **active id** (`NPC#getId`); list all variant ids. Never hand-edit `npc-voices.json`.

## 4. Rules

British medieval fantasy: commoners common British, royalty/knights/nobles posh
via style register, distinctive races and lore creatures keep their accents,
ethnicity = origin. Phrase accents positively, never mention America. No transient
comments. Gemini renders British/European accents reliably, foreign ones
inconsistently, don't over-invest where the model can't deliver.

## 5. Regenerate, verify, ship

```bash
python3 tools/generate_npc_voices.py
./gradlew test spotlessCheck
```

Spot-check resolutions with `run-game-client` + Debug Mode (`diagnose-npc-voice`),
then open a ~250-NPC PR linked to the batch issue. See `add-npc-profile`,
`regenerate-npc-voices`, and `create-issue` for the surrounding flow.
