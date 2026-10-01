# Flashalign baseline provider and toolchain decision

Date: 2026-09-12  
Mote task: `bd-01M2B113Y1DHQMD52K75T2NF2X`  
Repository HEAD: `fa015c38a1b481096646d2c857191c327fc7a9e5`

## Decision

Flashalign development uses the providers already admitted by the reframe4s
build. The CI ownership checkout now inspects those same immutable sources:

| Provider | Exact revision | Role |
|---|---|---|
| image4s | `ec56b34806c22e26c28ecbd366ef2e323195fc88` | Direct reframe4s source dependency and ownership authority |
| locus4s | `58c9739be51345ad9adc4bc9c9e7335023254ec9` | Transitive revision pinned by the admitted image4s build and ownership authority |
| Gale | `83cac90a678d1b8a31c590e0c1b8fc8bf3427161` | Direct reframe4s and image4s linear-algebra dependency |
| Ravel | `9c5669399ab8e2a11402e71973dd5f1e2f2c13f4` | Direct reframe4s and image4s array dependency |

The CI image4s checkout previously used `c1c9866...`, and its locus4s checkout
used `af063d7...`. Those revisions were valid historical ownership inputs, but
they did not describe the source closure compiled by the current build. The
workflow now uses `ec56b348...` and `58c9739...`. No provider pin in `build.sbt`
was changed.

The workflow SHA-256 changed from
`9c0574b051f937b7690d0eb17a8d6a7a25c8b2b360f72839cee478825099639d`
to
`a6139e4947094b02390777095263e069a2de87219f5c2f103fc86a07bddd78a8`.

## Bounded sbt exception

This repository remains on Scala `3.7.4` and sbt `1.11.7`. The parent Scala
workspace defaults to sbt `1.12.14`, but permits an exact bounded exception.
The Flashalign baseline takes that exception because:

1. `project/build.properties` already fixes `1.11.7` and CI reads that file;
2. the complete current JVM/Scala.js court passes with that launcher;
3. changing build tooling inside the algorithm baseline would combine a broad
   migration with the provider and graph prerequisite;
4. Flashalign adds no toolchain-specific API requirement at this stage.

Moving to sbt `1.12.14` requires a separate migration and a clean comparison of
generated metadata, all JVM/Scala.js/full-link gates, and downstream source
composites. This exception applies only to the current reframe4s baseline; it is
not a general rollback of the workspace default.

## Existing provider issues consulted

- `bd-01KYSQBDJ5WYPADYRPN9FMP0WA` tracks the earlier persistent-identity
  migration and its then-unpublished image4s source. This task consumes the
  later immutable `ec56b348...` revision and does not take over or close that
  issue.
- `bd-01KZ9E29X8ZFRVX94CD04NA2H1` tracks a separate Gale M1 candidate
  (`f869613...`). Flashalign currently uses the already admitted
  `83cac90a...` revision and does not require the unfinished candidate
  qualification.

Neither issue is a blocking prerequisite for this exact baseline. A future
Flashalign feature that requires output unique to either unfinished issue must
add the dependency when that implementation choice is made.

## Qualification receipt

The provider source checkouts were detached, clean clones. The exact image4s
commit has tree `d86d213b5802172c739393887edd1a12929f2a05`; the exact locus4s
commit has tree `109d8001a8fc3e5919f86eb3eaf0d8a0e31396be`.

With `IMAGE4S_ROOT` set to `ec56b348...` and `LOCUS4S_ROOT` set to
`58c9739...`:

```text
node scripts/verify-symbol-ownership.mjs
Symbol ownership is unique across 1048 public qualified names;
55 canonical owners verified.
```

The complete current build ran with Homebrew Java `25.0.1` and the declared sbt
`1.11.7`:

```text
sbt -J-Xmx4G -batch compileAll testAll
[success] Total time: 779 s (0:12:59.0)
```

All JVM and Scala.js projects compiled and tested. The long existing HalfFlow
court reported 143 JVM and 140 Scala.js passing tests. Its output remains
experimental evidence and does not become a Flashalign performance claim. One
existing real-data diagnostic improved landmark match (`15.38 mm` to `9.48 mm`)
while its reported CC decreased (`0.7898` to `0.7829`); that raw disagreement is
retained rather than interpreted as a new accuracy result.

The build resolved staged source checkouts at the declared direct revisions:

```text
Gale    83cac90a678d1b8a31c590e0c1b8fc8bf3427161
Ravel   9c5669399ab8e2a11402e71973dd5f1e2f2c13f4
image4s ec56b34806c22e26c28ecbd366ef2e323195fc88
```

The candidate includes unrelated, preexisting HalfFlow working-tree changes in
`build.sbt` and its module. They were neither edited nor claimed by this task.
The CI correction is path-scoped, and `git diff --check` passes.
