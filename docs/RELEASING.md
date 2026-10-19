# Releasing Lin

All builds are made by GitHub Actions; nothing is built by hand.

| | Rolling build | Numbered release |
| --- | --- | --- |
| Tag | `lin-dev`, moved to each new build | `lin-v0.2.0`, permanent |
| When | every push to `main` that changes the app | when you run the workflow with a version |
| Version name | `0.1.0-dev.<run>` | what you typed |

Both come from [`release_lin.yml`](../.github/workflows/release_lin.yml). It runs the unit tests,
builds release APKs for each ABI plus a universal one, writes `SHA256SUMS.txt`, and publishes them
with notes listing the recent commits and the signing certificate's fingerprint.

## Cutting a numbered release

**Actions → Release Lin → Run workflow**, type the version (for example `0.2.0`), run. About
15–25 minutes.

## Versions

`versionCode` is `1000 + the workflow's run number`, so every build is newer than the last and
Android accepts it as an update. Never reset it.

## Signing

Every build must be signed with the same key, or Android refuses to update an installed copy
(people would have to uninstall and lose their data).

The workflow signs with, in order of preference:

1. **Repository secrets** `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, if
   set. This is the right setup for a public repository: secrets are never given to workflows from
   forks.
2. Otherwise a keystore kept in the Actions cache (`star-ci-signing-key-v2`), created once and
   kept alive by [`keep-signing-key.yml`](../.github/workflows/keep-signing-key.yml), which reads
   it twice a week so GitHub does not evict it.

[`create-signing-key.yml`](../.github/workflows/create-signing-key.yml) makes a new keystore for
option 1. **Run it once at most**: a new key cannot update builds signed with the old one.
