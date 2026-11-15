# Terila Data Extractor

![Static Badge](https://img.shields.io/badge/version-26.3-green)
![AI-assisted](https://img.shields.io/badge/AI--assisted-heavy-blue)

Extracts the **complete** Minecraft data set — every registry, every data pack file, every structure
template, every protocol table and the whole resource pack — into JSON consumed by the Terila server.

Current target: **Minecraft 26.3** (protocol 777, data pack 121.0, world version 5023).

## Quick start

```bash
# everything: download Minecraft and every locale, run the game's data generator, then extract
./gradlew extractAll

# or step by step
./gradlew downloadMinecraft     # server bundler + client jar into .mc-cache
./gradlew downloadLanguages     # all 142 locales into .mc-cache/lang (client jar has en_us only)
./gradlew runDataGenerator      # Minecraft reports + full vanilla data pack into build/datagen
./gradlew runExtractor          # the extraction itself, into build/extractor_output
./gradlew runExtractor --args="--nbt-format raw"  # per-entry raw NBT tree
./gradlew test                  # unit + end-to-end output verification
./gradlew javadoc               # API documentation into build/docs/javadoc
```

`runExtractor` discovers the data generator reports, the client jar and the language directory
automatically, so a plain `./gradlew runExtractor` already covers every category. Point it elsewhere
with `--args="--reports <dir> --client-jar <jar> --lang-dir <dir> --output <dir>"`; anything left
out is still found through `TERILA_REPORTS`, `TERILA_CLIENT_JAR`, `TERILA_LANGUAGES` or a matching
`terila.*` system property. Repeat `--datapack <dir>` to stack data packs; later packs override
earlier ones, exactly as they do in a server.

A run writes into a sibling staging directory and publishes it only after extraction, validation
and the run-level reports have completed. A run that aborts before publication therefore leaves
the previous output untouched, and publication replaces only the managed layout: files at the
output root that the extractor does not own are never deleted. Pass `--no-clean` to write in
place and keep existing documents; in that mode stale documents remain part of the run and its
validation pass.

A run also takes an exclusive lock on its output directory. A second run pointed at the same
directory fails before it creates a staging directory or writes a document, instead of racing the
first. The lock is a file lock held for the whole run; its zero-byte lock file is a sibling of the
output, named `.terila-extractor-lock-<name>`, and is intentionally retained after release. Deleting
it could let a waiting process and a new one lock different files at the same path. A small
`.owner` file next to it records the holding PID and start time, which the conflict message
reports; it is replaced atomically, so a conflict never reads a half-written owner. It never blocks
a later run once the previous process has exited.

`--wait-for-lock <seconds>` waits up to that many seconds for the other run to finish; without it
(or with `0`) the run fails immediately. This is useful for wrappers that schedule one extraction
after another.

The head-less command exits with status `4` when the lock is already held; a genuine extraction
failure uses status `3`, so a wrapper can distinguish the two. `--help` lists every status.

No Minecraft installation is required: the build resolves the version from Mojang's version
manifest, verifies the SHA-1 of every download, and unpacks the bundler itself. An existing launcher
install in `~/.minecraft/versions/<version>/` is reused when present.

## How completeness is guaranteed

Three independent mechanisms run in the same pipeline, so no single one can silently lose data:

1. **Curated extractors** give each engine-facing data set a hand-picked shape (blocks, items,
   entities, recipes, tags, worldgen, ...).
2. **Sweep extractors** walk *everything* that exists rather than a list: `all_registries` covers
   every registry the game declares — built-in and data pack loaded — `all_tags` covers every tag
   binding of every registry, `structure_templates` covers every NBT template in the pack, and
   `functions` reads every `.mcfunction` source. A registry or tag category added by a future
   Minecraft release needs no code change.
3. **Report extractors** read what Minecraft itself writes with `net.minecraft.data.Main`, which is
   the only source for protocol-level tables (packet ids, global block state ids, registry protocol
   ids, the serialised command tree, default item components, the JSON-RPC API).

The run-level `_meta.json` lists every extractor, its entry count and its duration; `_errors.json`
carries every warning. A run with zero errors and zero unexplained empty documents is the contract
the test suite enforces.

## NBT output modes

`--nbt-format` selects how NBT is written. The default is `wire`, so existing scripts and the
checked-in golden digest keep the same bytes:

```bash
./gradlew runExtractor                              # wire (default)
./gradlew runExtractor --args="--nbt-format=raw"    # raw only
./gradlew runExtractor --args="--nbt-format=both"   # wire and raw
```

* **wire** keeps the packet-shaped files unchanged: `registries/nbt/...`, `tags/all.nbt`,
  `tags/client_tags.bin`, `recipes/minecraft.nbt`, `loot/minecraft.nbt`,
  `advancements/minecraft.nbt` and `commands/tree.nbt`.
* **raw** writes one unframed NBT file per entry plus a `manifest.json` under
  `registries/raw/<namespace>/<path>/` and the corresponding `recipes/raw/`, `loot/raw/`,
  `advancements/raw/`, `tags/raw/` and `commands/raw/` trees. There is no VarInt prefix, gzip
  header or outer compound wrapper; a compound entry starts directly with its own `0A` type byte.
* **both** writes both representations from the same in-memory payloads. This is useful for
  migration: the wire files stay byte-identical while consumers can move to per-entry NBT.

A registry raw manifest is a direct entry-id map; `file` is relative to the manifest directory and
is `null` for a built-in registry entry that has no NBT payload:

```json
{
  "minecraft:badlands": {
    "protocol_id": 0,
    "file": "minecraft_badlands.nbt"
  }
}
```

Recipe, loot, advancement and command manifests use the same direct-map principle. Tag raw files
are self-describing compounds containing `registry`, `id`, `replace`, resolved `values` and their
`protocol_ids`, so empty tags are retained exactly as the sweep resolved them.
The raw manifest files are named `manifest.json`; the tests parse every manifest and every emitted
NBT file, and verify registry `has_nbt` against the decoded wire payload.

## Output layout

```
build/extractor_output/
  _meta.json                  run summary: versions, per-extractor results, report hash
  _errors.json                errors and warnings raised during the run
  blocks/                     blocks, properties, materials, sounds, shapes,
                              blockstates/<block>.json, state_ids.json,
                              block_entities.json (block -> block entity type)
  items/                      items, components, component_defaults, food, tools, enchantable
  entities/                   entities, attributes, metadata types, poses
  recipes/                    recipes/minecraft.json, minecraft.nbt (counted id + NBT) and
                              shaped, shapeless, cooking, ... groups
  loot/  advancements/        JSON plus counted id + NBT wire streams
  predicates/  item_modifiers/
  functions/                  every .mcfunction source with its commands, comments,
                              macros, line counts, SHA-256 and function calls;
                              empty in vanilla
  tags/                       blocks, items, entities, fluids, game_events, functions, trades,
                              effective_functions.json (the game's merged, flattened view),
                              plus all.json: every tag of every registry (enchantments,
                              damage types, potions, instruments, worldgen, ...), resolved
                              and grouped by registry; by_entry.json is the inverse lookup
                              from an entry to every tag that contains it; all.nbt is the raw
                              update_tags (0x0E) payload for ClientboundUpdateTagsPacket;
                              client_tags.bin is that payload restricted to the registries a
                              vanilla client expects (see below)
  gameplay/                   game_rules.json (every rule, category, type and default) and
                              potions.json (every potion with its ordered effect instances)
  worldgen/                   biomes, features, placed features, structures, template pools,
                              processor lists, density functions, noise settings, dimensions,
                              biome parameters
  enchantments/  damage_types/  trims/
  translations/               en_us.json (the jar's table), keys.json (every key the
                              registries resolve, plus any that are missing),
                              locales.json (manifest: coverage per locale),
                              locales/<locale>.json (one document per locale)
  commands/                   tree.json (live dispatcher), tree.nbt (the same tree as
                              VarInt-length-prefixed NBT) and report.json (game's own serialisation);
                              raw/ contains the unframed tree and its manifest in raw/both mode
  packets/                    ids.json - every packet per state and direction;
                              structures.json - each packet's class, accepted state/direction,
                              protocol ids, payload fields and STREAM_CODEC components
  protocol/                   summary.json (versions, packet and registry counts;
                              packet_ids_available says whether the count is real),
                              feature_flags.json (the update_enabled_features vocabulary),
                              known_packs.json (the select_known_packs handshake),
                              json_rpc.json (26.x JSON-RPC methods and schema)
  registries/                 index.json (manifest of every registry)
                              dump/<namespace>/<path>.json (every registry, full payload)
                              nbt/<namespace>/<path>.nbt (every registry as a raw
                              registry_data (0x07) payload: full registry id, counted
                              id + has_nbt entries; the 60 data pack registries carry
                              entry NBT, built-ins carry identity)
                              raw/<namespace>/<path>/manifest.json and per-entry
                              .nbt files when --nbt-format is raw/both
                              report.json (protocol ids as the game reports them)
                              datapack_formats.json (what a data pack may define)
  structures/                 index.json plus templates/<namespace>/<path>.json;
                              each template keeps every palette (shipwrecks ship eight
                              wood variants), grouped block positions, block entities
                              with state_index/states, and entities
  assets/                     the client jar's resource pack: every JSON asset mirrored
                              under its original path, plus index.json and manifest.json
                              listing every file, binary ones included, with size and
                              SHA-256 (--copy-binary-assets also writes the binaries)
  misc/                       every remaining registry (sounds, particles, variants, dialogs,
                              stat types, position source types, command argument types, ...)
```

Structure templates keep all of their palettes. Twenty shipwreck templates carry eight wood variants
in the NBT `palettes` list; a template document's top level describes the first variant, and
`palettes` lists every variant with its own state table and block positions. `jigsaw_count` counts
jigsaw blocks by block id rather than by rendered state, so their orientation property cannot hide
them.

Every document is `{"_meta": {...}, ...payload}` with the extractor name, entry count and the
version triple, so a consumer can always tell which Minecraft version produced it.

### Four documents vanilla ships empty

Vanilla ships no `.mcfunction` sources, no function tags and no item modifiers. Rather than emitting a
bare empty array (which cannot be told apart from a failed extraction), `functions/minecraft.json`,
`tags/functions.json` and `tags/effective_functions.json` state `"vanilla_provides": false` with a
note, and `item_modifiers/minecraft.json` adds the complete type vocabulary the format is built from.
The test suite asserts that these are the *only* empty documents, so a real regression can never hide
behind them.

Functions are the one datapack data set that is not JSON. With one or more `--datapack <dir>`
overlays (later packs win) the extractor reads every `data/<namespace>/function/**/*.mcfunction` file
through the loaded packs and writes its source verbatim, together with line, command, comment and
macro counts and a SHA-256 hash. The same overlays' function tags are read from
`data/<namespace>/tags/function/`, so a stack's function graph stays usable end to end.

Each function also records its outgoing calls: `function`, `execute ... run function`, `execute
if/unless function`, `return run function` and `schedule function` are recognised, including nested
execute chains and function-tag targets. Calls are written with their source line, target type and
whether the target exists. A missing function or tag produces a `MISSING_FUNCTION_CALL` warning;
`call_count`, `dynamic_call_count`, `missing_reference_count` and `broken_function_count` summarize
the document. Targets using macros such as `function $(which)` are kept and marked `dynamic`, but are
not reported as missing because they cannot be resolved statically. Calls through a function tag are
expanded to the game's merged, flattened membership when the loaded function library is available
(falling back to the raw file resolution otherwise), so a loop that crosses a tag — including a
member merged in from a lower pack — is still visible. The root reports
`effective_tag_expansion_available`, `tag_call_count` and `tag_expansion_mismatch_count`, so a
consumer can tell which view expanded the calls and how many tag calls expanded differently from
the raw files. `tag_expansion_differences` names each called tag behind that count, with its
`call_count` and the `only_raw_functions`/`only_effective_functions` split. `call_cycle_count`,
`self_call_count` and `cyclic_function_count` summarize recursive groups, and
`cycles` lists each group with its sorted function ids and whether it is a self-call.
Cycles are reported as warnings rather than extraction failures, because bounded recursion is legal.

Function tags are also resolved rather than copied blindly. `tags/functions.json` records each tag's
declared `values`, every direct function and nested tag it names, its transitive
`resolved_functions`, and any `missing_references`. A reference marked `"required": false` is
allowed to be absent. `broken_tag_count`, `cyclic_tag_count` and `all_references_resolve` summarize
the graph, while each dangling required reference and each cycle is written to `_errors.json` as a
warning. A renamed function therefore shows up in the same run instead of turning into a command
that silently does nothing.

`tags/effective_functions.json` is the other half: it reads `ServerFunctionLibrary` (or
`ServerFunctionManager` in-game) and records the tags the game actually executes. That view already
has stacked packs merged, `replace: true` applied and nested `#tag` entries flattened, so a consumer
can see the difference between what a pack declares and what the server will run. This is also what
makes multi-pack merge and replace behaviour testable against the real loader instead of a
reimplementation of it.

`tags/functions.json` carries the same comparison inline, because a raw file only shows the winning
pack file. Each tag adds `effective_functions`, `effective_function_count` and `matches_effective`,
while the root reports `mismatched_tag_count`, `matches_effective_view` and an `effective_differences`
list with the members that only the declaration or only the game's merged view provides. A merged tag
is therefore visible in one document; a `replace: true` override that agrees with the raw file is not
reported as a difference. These are informational, not warnings: merging lower packs is what a
non-replacing tag is supposed to do.

Empty *tags* are different: vanilla declares ten tags with no entries (for example
`minecraft:incorrect_for_diamond_tool` and the dialog quick-action tags). An empty tag still says
"nothing matches", so `tags/all.json` keeps it with an empty `values` array, and the test suite pins
the exact set so a tag losing its entries is a reviewed change rather than a silent one. The wire
payload keeps empty tags too, because vanilla's own serialiser does: a registry whose tags are all
empty still belongs in the packet.

`tags/client_tags.bin` is the payload a client-bound `update_tags` packet can carry. Vanilla sends
only the registries a client takes tags for — 15 in 26.3 — and a client rejects the whole packet
when that set does not line up with its own. The set is versioned data in
`config/versions/<version>/tag_registries.json` (`protocol` plus the ordered
`client_tag_registries` list), so a future version needs a new file, not a code change. `all.nbt`
keeps every registry the sweep found; `client_tags.bin` copies the whitelisted registry blocks from
it byte-for-byte, in the configured order, and writes `tag_count = 0` for a whitelisted registry
that has no tags. At run time the extractor also asks the game's own
`TagNetworkSerialization.serializeTagsToNetwork` which registries it would send, and reports a
`TAG_REGISTRY_DRIFT` warning when that differs from the configuration. That is a diagnostic, not a
failure: the capture stays the source of truth, because a capture can carry a registry this
extractor has no tag binding for.

## License

This project is licensed under the **Apache License, Version 2.0**. See [LICENSE](LICENSE).
Minecraft itself, its data packs, client assets and language files remain subject to Mojang's
terms; this repository only contains the extractor and generated output.

## Requirements

* JDK 25+ (the build targets Java 25, the same runtime 26.3 uses)
* Network access for the first build only
* ~550 MB of disk: a wire extraction is ~230 MB (structure templates ~65 MB, the mirrored
  resource pack ~17 MB), plus ~100 MB of downloaded Minecraft artifacts and the data generator's
  own output. Raw mode adds one small file per entry (about 14,000 `.nbt` files in vanilla).

## Notes on 26.3

The port needed four API changes, each verified against the game:

* `ConfiguredFeature` was folded into `Feature`; the `minecraft:feature` registry now stores the
  feature with its config. The feature type vocabulary moved to `minecraft:worldgen/feature_type`,
  which is what `misc/feature_types.json` reads. Carvers have the same split: `worldgen/carver`
  holds configured carvers and `worldgen/carver_type` the vocabulary.
* `Registry.byNameCodec()` became a *name* codec — it encodes an entry as its identifier. Registry
  dumps therefore use the element codec the game loads data pack files with
  (`RegistryDataLoader.RegistryData#elementCodec()`), otherwise every document collapses to a list of
  names. `RegistryCodecsTest` pins this.
* Recipes, advancements, loot tables, predicates and item modifiers became data pack registries.
* Codecs have to be serialised against the composite registry access: the reloadable holder's own
  lookup does not cover those new registries, and encoding an advancement that references a recipe
  fails with "is not valid in current registry set". The same applies to item components — a block
  transformer, an equipment asset, a jukebox song or a repair tag is a registry reference, so
  serialising components with bare `JsonOps` silently dropped 265 payloads to `null` until the
  registry lookup was attached. `ItemExtractorTest` now compares all 1658 component maps against the
  game's own report, so that class of loss cannot return unnoticed.

The 26.3 client jar ships only `en_us.json`; the other 142 locale files are launcher assets.
`./gradlew downloadLanguages` fetches them from Mojang's asset index (SHA-1 verified, same cache as
the jars) and `translations/` then contains a document per locale. `translations/locales.json`
records, per locale, how many keys it carries and how many of the registry keys it translates, so
translation coverage is measurable instead of assumed. Without the download the extractor still
produces `en_us` plus the manifest, and says so.

## Verification

The test suite (254 tests) re-runs the full wire pipeline once and runs a separate raw/both fixture;
it checks the result six ways: every
document against its schema, every count against the live game (block state ids, recipes, templates,
packets), every item's component map against the data generator's own per-item report, every
document against a checked-in digest of its normalised content, a pack census that counts every
registry, tag and `.mcfunction` source file and compares it with the documents, and a binary NBT
pass that decodes every registry and tag NBT file through the game's own packet codecs, rejects
gzip and compound wrappers, checks registry ids and entry order, and verifies there is no trailing
byte; the NBT bytes are also pinned by the golden digest. `recipes/minecraft.nbt`,
`loot/minecraft.nbt`, `advancements/minecraft.nbt` and `commands/tree.nbt` are covered by the same
pass. A second fixture runs the same NBT extractors in `both` mode and checks every raw manifest,
every standalone NBT file and the raw-only/wire separation; it also compares all 160 wire NBT files
and `tags/client_tags.bin` byte-for-byte with the default fixture. Two independent runs of `./gradlew runExtractor` are
byte-identical apart from the documented volatile members (timestamps, durations, the report hash
and absolute input paths), so the digest only moves when the extracted data itself changes:

```bash
./gradlew runExtractor --args="--output build/out_a"   # then compare build/out_a with build/out_b
```

**The vast majority of the project was created using artificial intelligence.**