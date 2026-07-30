# cloud-itonami-esim

[![CI](https://github.com/cloud-itonami/cloud-itonami-esim/actions/workflows/ci.yml/badge.svg)](https://github.com/cloud-itonami/cloud-itonami-esim/actions/workflows/ci.yml)

Open Business Blueprint for **eSIM remote provisioning**: eUICC registration,
profile download and install, profile lifecycle (enable / disable / delete),
SM-DS event registration, and subject-level ownership transfer. This repository
publishes a governed eSIM-provisioning actor as an OSS business that any
qualified operator (a licensed mobile operator, an MVNO, or a provisioning
platform acting for one) can fork, deploy, run, improve and sell.

Built on this workspace's
[`langgraph-clj`](https://github.com/kotoba-lang/langgraph) StateGraph runtime,
the same actor pattern as every other actor in this fleet — here it is
**eSIM Provisioning Advisor ⊣ eSIM Provisioning Governor**.

> **Why an actor layer at all?** An LLM is fine at drafting a provisioning
> summary and normalising records, but it has **no notion of whether an EID can
> structurally exist, whether an ICCID passes its own check digit, whether a
> profile transition is reachable from the state on record, whether enabling a
> profile would silently cut a working line, or when a request stops being a
> draft and becomes a real number takeover**. Letting it commit directly invites
> a profile enabled on top of someone's live line and a SIM swap approved
> because it looked routine. This project seals the Advisor into a single node
> and wraps it with an independent **Governor**, a human **approval workflow**,
> and an immutable **audit ledger**.

## Why the library is the authority

[`kotoba-lang/esim`](https://github.com/kotoba-lang/esim) owns identifier
structure (EID, ICCID) and the profile lifecycle state machine. **This actor
holds no second copy of those rules.**

| fact | decided by |
|---|---|
| EID structure | `kotoba.esim/validate-eid` |
| ICCID check digit (ITU-T E.118) | `kotoba.esim/validate-iccid` → Luhn via `kotoba.card` |
| profile reachability | `kotoba.esim.lifecycle/apply-operation` |
| single-enabled-profile invariant | `kotoba.esim.lifecycle/enabled-conflict` |
| identifier masking | `kotoba.esim.export/mask-identifier` (aliased, not reimplemented) |

This is a deliberate correction of two things that happened in the sibling
[`cloud-itonami-card-issuing`](https://github.com/cloud-itonami/cloud-itonami-card-issuing):
it reimplemented `luhn-check-digit`/`luhn-valid?` locally although
`kotoba.card/luhn-valid?` already existed, and it kept its lifecycle allowlist
only in its own governor — so `kotoba.card.lifecycle` had to be corrected to
mirror it after the fact (2026-07-30). Starting from the library avoids both.

Two rules follow from that, and both are enforced in code:

- **The governor recomputes every ground-truth fact** through the library and
  over recorded store state, never off the proposal. A high self-reported
  `:confidence` buys nothing.
- **The store applies what the *governor* computed**, never the payload the
  advisor claimed. `governor/cleared-transition` produces the outcome and
  `operation/commit-record` attaches it; if the advisor's own profile vector
  could reach the store, the reachability check would be decorative.

## The gates

Nine governor checks. The first seven are **HARD** — a human approver cannot
override them:

1. **Effect matches op** — the proposal's `:effect` must be the one legitimate
   effect for the *request's* `:op`. Without this first, an advisor could answer
   a harmless `:coverage/report` with `:effect :ownership/transfer-requested`,
   and a human approving what looks like a report would trigger a transfer with
   none of its scrutiny run.
2. **EID invalid** — structurally impossible eUICC identifier.
3. **ICCID invalid** — check digit failure.
4. **eUICC unknown** — cannot operate on an eUICC that was never registered.
5. **Transition unreachable** — recomputed over the eUICC's own profiles.
6. **Enable would displace** — another profile is already enabled; an eUICC has
   at most one, so this would cut a working line as a side effect. Reported as
   its own rule so the ledger distinguishes *not reachable* from *would cut a
   line*.
7. **Transfer invalid** — the recorded subject must match the claimed
   from-subject and differ from the to-subject.

The last two are **SOFT** (a human looks, and may approve): low confidence, and
`:stake :actuation`.

## Actuation

`:profile/download`, `:profile/lifecycle` and `:ownership/transfer` are **absent
from every phase's `:auto` set, including phase 3**. This is a permanent
structural fact about
[`esimprovisioning.phase`](src/esimprovisioning/phase.cljc), not a rollout
milestone still to come:

- a download or lifecycle transition changes whether a real person's line works;
- an **ownership transfer is the primary SIM-swap fraud path**, and whoever
  takes over a phone number has taken over the second factor for everything
  else.

The governor's `:actuation` high-stakes gate enforces the same invariant
independently — two layers, not one, agree on this.

**An ownership transfer is recorded as a REQUEST and is never executed here.**
`apply-commit!` writes `:executed false` and deliberately does **not** reassign
the subject of record; executing a transfer is a licensed operator act outside
this actor.

## Coverage

**There is no per-jurisdiction spec-basis catalog at R0, and coverage is
reported as zero rather than invented.** The sibling card-issuing actor has one
(`cardissuing.facts`); the analogous thing here would be telecom licensing plus
GSMA SAS-SM accreditation. Seeding it would mean asserting jurisdiction-specific
requirements this build has not independently verified — which is exactly the
failure
[`cloud-itonami-isic-6120`](https://github.com/cloud-itonami/cloud-itonami-isic-6120)'s
ADR-0002 records (fabricated Radio Act / FCC / Ofcom / TKG citations). An empty
catalog that holds is honest; an invented one is not.

Adding a jurisdiction is additive and requires a real, cited official source —
follow `cardissuing.facts`' shape, and do not invent requirements to make
coverage look bigger.

## Fixtures

The demo EID and ICCIDs in `esimprovisioning.store/demo-data` are **synthetic**.
The ICCIDs carry real ITU-T E.118 / Luhn check digits so that checksum
validation is genuinely exercised rather than stubbed, but no real subscriber,
operator or device is represented. `registry_test.clj` asserts this — the
fixtures must actually pass the library's checks, and the tampered one must
actually fail.

## Run

```bash
clojure -M:dev:test    # 31 tests / 167 assertions
clojure -M:lint        # clj-kondo, 0 errors 0 warnings
clojure -M:dev:run     # end-to-end demo: offline, no model, no network
```

The demo drives five operations through one compiled actor and prints the
ledger — an auto-commit, two HARD refusals (bad check digit; enable that would
displace), a human-approved disable, and an ownership transfer whose record ends
up `executed? false`.

## Maturity

| | |
|---|---|
| Role | governed actor (Advisor ⊣ Governor ⊣ append-only ledger) |
| Tests | 31 tests / 167 assertions, all green |
| Lint | clj-kondo 0 errors, 0 warnings |
| Store backends | MemStore only — Datomic/kotoba-server is the next seam |
| Jurisdiction spec-basis | none at R0, deliberately (see Coverage) |
| Real SM-DP+ / SM-DS connection | none — ports are host-injected, see `kotoba.esim.ports` |
| Actuation | never; every op is `:effect :propose` |

## Design authority

Step 4 of the build order in **ADR-2607300300** (`com-junkawasaki/root`,
`90-docs/adr/`), which integrates eSIM, inbound voice reception and card issuing
onto one identity subject in `cloud-itonami-app`. That ADR records why this actor
is new rather than an extension of
[`cloud-itonami-isic-6120`](https://github.com/cloud-itonami/cloud-itonami-isic-6120):
6120's primary entity is a SITE (cell tower, spectrum licence), and its own
ADR-0002 is the record of *correcting* an earlier MSISDN-centric implementation —
adding profile lifecycle there would reverse that correction.

## License

AGPL-3.0-or-later. See [LICENSE](LICENSE).
