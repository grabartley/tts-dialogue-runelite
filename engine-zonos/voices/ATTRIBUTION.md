# Reference-voice bank

The Zonos engine conditions synthesis on a speaker embedding derived from a short reference clip.
This directory holds one clip per reference-voice id the plugin's `ZonosVoiceMap` can request, so
the engine can resolve any plugin `voice{race,gender,player}` to a concrete embedding.

## Required clips

One `<id>.wav` is required for every id below (the build script `packaging/build_bundle.py` asserts
this before producing a bundle and fails the build if any is missing):

- `narrator_neutral` (default / fallback voice)
- `player_male`, `player_female`
- `human_male`, `human_female`
- `elf_male`, `elf_female`
- `dwarf_male`, `dwarf_female`
- `goblin_male`, `goblin_female`
- `troll_male`, `troll_female`
- `undead_male`, `undead_female`
- `demon_male`, `demon_female`
- `wizard_male`, `wizard_female`

Each clip should be a few seconds of clean mono speech with the character of that race/gender.

## Status

The `.wav` clips are **not committed to the repo** (they are audio assets, kept out of the Java
plugin jar and out of source control). The release workflow stages them into this directory before
calling the build script, from a maintainer-provided source. Until clips are supplied, a real bundle
cannot be built; the framing/protocol code, packaging, and CI wiring are complete and validated
independently.

## Licensing

Track the provenance and license of each clip here as it is added, e.g. originally produced
recording, or an openly licensed source with attribution. Clips are used only to derive an embedding
at runtime and are not redistributed as a standalone audio product.
