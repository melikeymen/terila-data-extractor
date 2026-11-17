# Security policy

## Supported versions

The `main` branch and the latest tagged release are supported. The extractor targets one Minecraft
version at a time (currently 26.3); fixes land on `main` and are released from there.

## Reporting a vulnerability

Please do not open a public issue for a security problem.

* Use GitHub's private vulnerability reporting: **Security → Report a vulnerability** on this
  repository. That channel is private to the maintainers and lets us discuss and fix the issue
  before it is disclosed.
* If you cannot use it, contact a maintainer directly through their GitHub profile and say that you
  have a security report.

A useful report contains:

* the affected component (extractor, build script, git hook, CLI) and version or commit,
* the impact you believe it has,
* the smallest reproduction you can manage (command line, input pack, document),
* any suggested fix.

## What to expect

* We acknowledge a report within seven days.
* We confirm or reject the report and outline the fix within thirty days.
* We credit reporters in the release notes unless they ask us not to.

This is a volunteer project without a bug bounty; there is no payment for reports.

## Scope

In scope:

* the extractor, its CLI and build scripts, including the git hooks and the catalog generator,
* unsafe handling of untrusted input (a data pack, a resource pack, a jar supplied on the command
  line),
* path traversal, symlink or archive handling in the readers and the atomic output writer,
* anything that could leak a contributor's local paths, secrets or environment into a committed
  file.

Not in scope:

* inaccuracies in Minecraft data itself. Wrong counts, missing entries or a changed API are ordinary
  bugs: report them with the issue templates.
* vulnerabilities in Minecraft, the JDK or Gradle. Report those upstream.
* the contents of downloaded Mojang artifacts. They are fetched from the official version manifest
  and verified by SHA-1; they are never committed to this repository.

## Handling of secrets and Minecraft binaries

This repository contains only the extractor and its generated output. Do not attach Mojang jars,
data packs, client assets or language files to reports; describe them instead. Never paste access
tokens, API keys or `.env` contents into an issue, a pull request or a log excerpt.
