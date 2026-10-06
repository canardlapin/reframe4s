# Project state and cross-machine continuation

Mote is the authoritative tracker for Reframe4s tickets, decisions, claims,
reservations, and work history. Git carries the canonical store identity in
`FORMAT.json` and the immutable operation journal in `ops/*.json`. Machine
actor settings in `local/` and temporary writes in `tmp/` are ignored.

## Starting on another machine

Clone or pull the repository and install the Mote CLI. Git does not preserve
empty directories, so create the ignored machine directories before using the
store. Choose an actor name unique to your machine or workstream:

```sh
mkdir -p .mote/local .mote/tmp
mote actor set reframe4s-my-machine
mote doctor
mote in-flight
mote ready
```

The repository's `FORMAT.json` preserves the existing project identity. Do not
replace it or recreate the issue graph from an old plan. `mote doctor` checks
the journal, and `mote show ISSUE` retrieves an issue's current state and history.

Use a distinct session actor when several agents share a checkout:

```sh
eval "$(mote session start --as reframe4s-my-session --label 'Reframe4s work')"
```

An old actor's session, claim, or reservation is not ownership for a new agent.
Inspect current ownership and use the normal Mote claim, reservation, and
handoff commands for the selected issue.

## Keeping the journal shared

Use Mote commands for mutations; never hand-edit or delete operation files.
Pull before selecting work, and commit and push journal additions with the work
they record. Before publishing journal entries, check that notes contain no
credentials, restricted data, or private messages.

Git provides durable replication, not instant coordination between machines.
Separate clones must exchange journal additions and inspect current claims
before working on the same paths. A stale or offline clone cannot establish
exclusive ownership on another machine. Preserve both journals when reconciling
concurrent work and validate the combined store with `mote doctor`.

Source, plans, compact scientific evidence, and reproduction instructions are
tracked elsewhere in this repository. Large MRI/map binaries under
`benchmarks/flashalign/local-results/` and local `.agent-work/` archives need
separate transfer or reproduction when a task requires them. See
[dataset retrieval and migration](../docs/datasets.md),
[validation](../docs/validation.md) and
[acquired-image reproduction](../docs/benchmarks/real-image-development.md).
