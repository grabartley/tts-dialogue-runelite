# Emotion detection: chat-head expression seq ids

The plugin reads the speaker's chat-head animation off the dialogue widget
(`InterfaceID.ChatLeft.HEAD` for NPC lines, `InterfaceID.ChatRight.HEAD` for player lines) via
`Widget.getAnimationId()`, then maps that seq id to an `Emotion` through the bundled
`src/main/resources/expression-emotions.json` table. The mapped emotion is what the cloud backend
renders (see [backends.md](backends.md)). Any id not in the table, and `-1`, resolve to `NEUTRAL`.

## How OSRS actually exposes dialogue expressions

There is **no single universal expression enum**. OSRS dialogue chat-head expressions come in two
shapes, both living in the cache's seq namespace (`seqtypes`):

1. **A generic default block** (seq ids ~554-617) named `chat<mood><n>`, which the standard dialogue
   system uses for the player and most NPCs. The emotional ones:
   - `chathap1-4` (567-570) and `chatlaugh1-4` (605-608) -> **HAPPY**
   - `chatsad1-4` (610-613) -> **SAD**
   - `chatang1-4` (614-617) -> **ANGRY**
   - `chatscared1-4` (596-599) and `chatshock1-4` (571-574) -> **SCARED**
   - the rest (`chatneu`, `chatquiz`, `chatbored`, `chatcon`, `chatshifty`, `chatdrunk`, `chatskull`,
     `chatgoblin`, `chatent`, `chatidleneu`, ...) are **NEUTRAL** and are deliberately not listed.
2. **Per-NPC expression heads**, named `<npc>_chat[head]_<mood>` (e.g. `lore_lizard_chat_happy`,
   `peng_chat_sad`, `kahlith_chat_disapproving`, `werewolf_update_angry_chathead`,
   `my2arm_troll_chathead_cry`). Each is its own seq id, scattered across the cache.

Because emotion is encoded in the seq **name**, the table is built by harvesting every chat-head seq
whose name carries an emotion word and bucketing it. Everything else stays NEUTRAL by default, so the
table only needs the non-neutral ids.

> Note: a previous version of this table used ids `9760-9862` copied from a 2011-era ("508") private
> server chat-head list. Those ids are reused by live OSRS for Tombs of Amascut / combat-achievement
> animations, so they never matched a real dialogue head. Always derive ids from the live cache.

## Regenerating the table (when Jagex adds new expressions)

The source of truth is the OSRS cache seq **name** dump, `seqtypes.txt` (id `<TAB>` name per line). A
convenient copy ships with the [`@jayarrowz/mcp-osrs`](https://github.com/jayarrowz/mcp-osrs) data
dir; otherwise dump it from a current cache via [openrs2.org](https://archive.openrs2.org) with a
tool like [zwyz/osrs-cache](https://github.com/zwyz/osrs-cache).

1. Point `SEQ` at a current `seqtypes.txt`.
2. Harvest every chat-head seq carrying an emotion word:

   ```bash
   grep -iE '\t([a-z0-9_]*chat[a-z0-9_]*)$' "$SEQ" \
     | grep -iE 'hap|laugh|smile|cheer|joy|grin|chuckle|glee|sad|cry|weep|sob|mourn|tear|upset|distress|ang[0-9]|angry|anger|rage|disapprov|furious|snarl|scowl|annoy|grump|scared|shock|fear|afraid|terrif|fright|panic|worried|nervous' \
     | sort -t$'\t' -k2
   ```

3. Bucket each hit into the four non-neutral emotions, applying judgement on edge cases:
   - **HAPPY**: `hap`, `laugh`, `smile`, `cheer`, `grin`
   - **SAD**: `sad`, `cry`, `weep`, `sob`, `mourn`, `tear`
   - **ANGRY**: `ang`/`angry`, `rage`, `disapprov`, `furious`, `snarl`, `scowl`, `annoy`
   - **SCARED**: `scared`, `shock`, `fear`, `afraid`, `terrif`, `fright`, `panic`, `worried`
   - **NEUTRAL (omit)**: `idle`, `talk`, `listen`, `basic`, `nod`, `shake`, `quiz`/`quizzical`,
     `chant`, `mute`, `bored`, `con`, `shifty`, `drunk`, `skull`, plus any `*_idle` resting pose of an
     otherwise-emotional head (e.g. `chathap_idle`).
4. Write only the non-neutral ids into `expression-emotions.json` (id -> emotion, neutral omitted).
5. Update the entry count asserted in `ExpressionEmotionTableTest.documentedIdsResolveToTheirEmotion`
   and any representative ids in the resource tests.

### Verifying an id in the field

Enable **Debug Logging**; each line logs `resolved emotion <E> for head animation <id>`. Cross-check the
`<id>` against `seqtypes.txt`: if its name carries an emotion word that isn't mapped yet, add it.
`mcp-osrs`'s `search_seqtypes` (or a plain `grep '^<id>\t' seqtypes.txt`) confirms what any id is.
