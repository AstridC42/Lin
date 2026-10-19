# Contributing to Lin

Thanks for helping. Bug reports, ideas, docs and code are all welcome.

## Reporting a bug or asking for a feature

Open an [issue](../../issues/new/choose). For a bug, say which Lin version (Android Settings → Apps → Lin shows it),
the phone and Android version, which model answered (on the phone or your server, and which),
and what you said to Lin. A screenshot helps a lot.

Security problems: please do not open a public issue; see [SECURITY.md](SECURITY.md).

## Making a change

1. Fork the repository and create a branch from `main`.
2. Build and run it: [docs/BUILDING.md](docs/BUILDING.md).
3. Make the change. Read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) first if you are adding a
   tool or touching the turn loop.
4. Add or update tests. Logic belongs in `core/` as plain functions with unit tests wherever it
   can be.
5. Run the checks:
   ```bash
   ./gradlew :core:testDebugUnitTest :engine:testDebugUnitTest :app:testDebugUnitTest :app:lintVitalRelease
   ```
6. Open a pull request that says what changed and why, and how you tested it (on which phone, if
   it is something tests cannot show).

Keep pull requests focused: one fix or feature each is far easier to review.

## Principles

These are what make Lin Lin; changes that work against them will be asked to change.

- **Private by default.** No analytics, no accounts, no new network destinations the person did
  not choose. Keys stay on the phone.
- **Tell the truth on screen.** Never show something as done that was only opened, staged or
  attempted. Never let a model's claim stand in for what a tool actually did.
- **Staged before sent.** Anything that reaches another person or cannot be undone waits for the
  person to confirm.
- **Works with small models.** Tool descriptions and prompts are written for a 2B model on a phone,
  not only for a large server model.

## Code style

Kotlin with 2-space indentation (see `.editorconfig`), Jetpack Compose for UI, Hilt for
injection. Comments explain why, not what. New files start with the Apache 2.0 header used in
the existing ones, with `Copyright 2026 The Lin Authors`.

By contributing, you agree that your contributions are licensed under the
[Apache License 2.0](LICENSE).

Everyone taking part is expected to follow the [Code of Conduct](CODE_OF_CONDUCT.md).
