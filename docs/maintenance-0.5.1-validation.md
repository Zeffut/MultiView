# 0.5.1 maintenance validation

## Changes

- Coalesce shared entity identities across POVs, track observer lifetimes and clean up at source end.
- Pre-index entity identity intervals without retaining packet bodies; do not bind an ambiguous reference to a future incarnation.
- Use a single output segment to avoid invalid snapshot tick markers and inconsistent secondary snapshots during seeks.
- Preserve real secondary player profiles, including signed properties; parse local-player velocity before the GameProfile.
- Fix duplicate UUID serialization in synthesized Player Info packets and permit full-profile upgrades after a partial announcement.
- Remap proven projectile owner fields. Unsupported causal order fails explicitly instead of emitting a corrupt final replay.
- Permit merges of disjoint sources in both UI variants.

## Executed gates before CI

All five targets (1.21.9, 1.21.11, 26.1, 26.2, 26.3) compile and pass the automated matrix: 243 declared tests, 12 skipped per target, no failures/errors. The separate 26.2 real-corpus run has 243 declared tests, 7 skipped, no failures/errors. The real corpus is local test data and is deliberately not committed; CI will not claim to run these skipped corpus tests.

The 26.2 candidate was exercised in a standalone Flashback 0.43.6 client:

- Actual JAR SHA-256: `03fd8f010c9aa2f5f93ccde02e5c0f56dbdaddfa2bfb407fc627daef8b184f7b`.
- A 9217-tick disjoint-source replay seeks past 6000, to 8012, to the end and back to zero.
- A separate continuous normal-speed playback traverses the entire replay from zero to 9217, including the formerly failing Player Info region after 8160.
- `PovBeta` has the correct visible name; the witness cow is present. No duplicate-UUID or packet-decoder error was observed.
- Offline-account Realms/profile-authentication errors are not replay decoder errors and were not hidden.

The signed-profile integration regression covers both empty and non-empty unsigned initial profiles and signed announcements before and after CreatePlayer. Encoded-packet tests cover identity reuse and owner rejection, including the absence of a final ZIP after a causal-order failure.

## Explicit limits

The GUI corpus has no projectiles and no real signed texture properties. These cases have deterministic codec/integration coverage, not visual coverage. An owner that has not yet been emitted causes an explicit merge failure; it is not silently dropped and is not claimed as supported. Large-replay heap stress and GUI tests on the other four targets have not been completed. A single-segment output is not a claim of universally bounded client playback memory.

Publication still requires independent review, successful CI on the exact source commit and artifact comparison. This document is a record of checks, not a publication assertion.
