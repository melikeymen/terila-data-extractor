# Contributing

Thanks for taking a look. This repository extracts the Minecraft data set into JSON and NBT for the
Terila server; the interesting part is that every category is covered, versioned and verified, so
changes are expected to keep the generated output consistent and reproducible.

## Requirements

* JDK 25 or newer (`java_version` in `gradle.properties`) — the build targets the same runtime the
  game uses.
* Git, and a network connection for the first build (Minecraft artifacts are downloaded from
  Mojang's official version manifest and verified by SHA-1).
* About 550 MB of disk for a full extraction plus the downloads; see the README.

## First steps

```bash
git clone <your fork>
cd terila-extractor
./gradlew build          # compiles, runs the test suite and installs the git hooks
```

`build` also runs `installGitHooks`, which copies the versioned hooks in `.githooks/` into your
checkout. You can run it on its own after changing a hook:

```bash
./gradlew installGitHooks
```

## Everyday commands

| Command | What it does |
|---|---|
| `./gradlew build` | Compile, run the full test suite, install the hooks |
| `./gradlew test` | Tests only (the full pipeline plus the raw/both fixture) |
| `./gradlew extractAll` | Download Minecraft and every locale, run the data generator, then extract |
| `./gradlew runExtractor` | Extract into `build/extractor_output` using what is already cached |
| `./gradlew runExtractor --args="--nbt-format both"` | Same run with both NBT representations |
| `./gradlew generateOutputCatalog` | Rewrite `OUTPUT_CATALOG.md` from `build/extractor_output` |
| `./gradlew javadoc` | API documentation into `build/docs/javadoc` (doclint clean) |
| `./gradlew versions` | Print the Minecraft, protocol, data pack and world versions |

## The output catalog is generated

`OUTPUT_CATALOG.md` is a checked-in, generated document: it describes a real extraction, so it is
never edited by hand.

* `./gradlew generateOutputCatalog` rebuilds it from `build/extractor_output`.
* The versioned `.githooks/pre-commit` hook runs that task and stages the result before every
  commit, which is how a change to the extractor output and the catalog that describes it end up in
  the same commit.
* Without an extraction in `build/extractor_output` (a fresh clone, CI) both the task and the hook
  leave the committed file untouched, so nothing is ever replaced with an empty catalog.

If your change alters what the extractor writes, run the extraction (or at least the affected
document), commit the regenerated catalog with it, and mention the data change in the commit
message.

## Tests and the golden digest

`./gradlew test` re-runs the full wire pipeline and a separate raw/both fixture, then checks the
result against the schemas, the live game, a pack census and a checked-in digest of every
document's normalised content.

A golden mismatch is a real change, not a flake. Update the baseline only when the extracted data
itself changed for a reason you can explain:

```bash
./gradlew test --tests 'com.terila.extractor.GoldenOutputTest' -Dterila.golden.update=true
```

Never update the digest to make an unexplained failure pass; that removes the only defence against
silent data loss.

## Adding an extractor

1. Register it in `Extractors` / `ExtractorRegistry` and give it a `DataType`.
2. Add the document schema in `DocumentSchemas` so the output is validated.
3. Write the document through `JsonWriter`/`NbtFormat` as the neighbouring extractors do, and record
   the entry count in `_meta`.
4. Extend the tests: coverage is asserted against the live game wherever a count can be compared.
5. Run `./gradlew test`, then `./gradlew generateOutputCatalog` and commit both.

## Supporting a new Minecraft version

1. Bump `minecraft_version`, `minecraft_protocol_version`, `data_pack_version`,
   `minecraft_world_version` and `minecraft_resource_pack_version` in `gradle.properties`.
2. Add `config/versions/<version>/tag_registries.json` (the `protocol` and the ordered
   `client_tag_registries` list). The whitelist is versioned data, so a new version needs a file,
   not a code change.
3. Port the code where the game's API moved, and keep the notes in the README's version section in
   sync.
4. Re-run `./gradlew extractAll`, update the golden digest deliberately, and regenerate the catalog.

## Style

* Java 25, UTF-8, LF, four-space indent (see `.editorconfig`), no wildcard imports.
* Javadoc is written in English and has to keep `./gradlew javadoc` clean: broken `{@link}`
  references, malformed HTML and syntax errors fail the build. The `missing` group is disabled on
  purpose; document behaviour, and add `@param`/`@return` where they carry information.
* Prefer the existing helpers (`JsonWriter`, `AtomicFileWriter`, `IdentifierUtil`, ...) over new
  ones, and avoid new runtime dependencies unless the feature genuinely needs them.
* Keep the build working on Windows, macOS and Linux: machine-specific paths belong in
  `terila.minecraft.dirs` / `TERILA_MINECRAFT_DIRS`, never in a committed file.

## Commits and pull requests

* One focused change per commit, with a message that says why the change was made.
* Say in the pull request whether generated files changed: `OUTPUT_CATALOG.md`, the golden digest,
  or a document under `config/versions/`.
* CI runs `./gradlew build` and `./gradlew javadoc` on JDK 25.
* Do not commit Mojang jars, data packs, client assets or language files; only the extractor and
  its generated output belong in this repository.

## Reporting bugs and getting help

* Use the issue templates; include the output of `./gradlew versions`, your OS and JDK version, and
  the relevant `_errors.json` entries.
* Security problems go through [SECURITY.md](SECURITY.md), not the public issue tracker.
* Participation is governed by [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).

## License

This project is licensed under the Apache License, Version 2.0. By contributing you agree that your
contribution is licensed under the same terms; there is no separate CLA to sign. Please keep the
existing copyright and license notices intact.
