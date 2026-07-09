# ADR-0001: FuelTradeAdvisor ⊣ Fuel Trading Governor architecture

## Status

Accepted. `cloud-itonami-isic-4671` promoted from `:blueprint` to
`:implemented` in the `kotoba-lang/industry` registry.

## Context

`cloud-itonami-isic-4671` publishes an OSS business blueprint for
wholesale of solid, liquid and gaseous fuels (fuel-order intake, per-
jurisdiction contract / sanctions / fuel-excise regulatory
verification, bulk-fuel delivery, and invoice settlement). Like every
prior actor in this fleet, the blueprint alone is not an
implementation: this ADR records the governed-actor architecture that
promotes it to real, tested code, following the same langgraph
StateGraph + independent Governor + Phase 0->3 rollout pattern
established by `cloud-itonami-isic-6511` (life insurance) and applied
across many prior siblings, most recently the crude-extraction sibling
`cloud-itonami-isic-0610`.

Like the crude-extraction sibling and `cloud-itonami-isic-0162`
(community agronomy), this vertical has NO bespoke domain capability
library in `kotoba-lang` to wrap (verified: no
`kotoba-lang/fueltrade`-style repo exists, and `kotoba-lang/robotics`
is the generic cross-cutting robotics contract every cloud-itonami
vertical already uses, not a domain-specific library for this
vertical). This build therefore uses self-contained domain logic -- the
same pattern the majority of this fleet's actors use, and the explicit
differentiator from `cloud-itonami-isic-4920` (which wraps a
pre-existing `kotoba-lang/logistics` library). The fuel-trading checks
(credit-clearance, contract-on-file, sanctions-screening) are direct
entity boolean reads in `fueltrade.governor`, off dedicated
`:credit-cleared?` / `:contract-terms` / `:sanctions-screened?` facts
on the `fuel-order` record -- NO pure range-check functions are needed
(contrast the crude sibling, whose registry hosts its reservoir/
annular/water-cut/H2S range checks).

This blueprint's own `:itonami.blueprint/governor` keyword,
`:fuel-trading-governor`, is grep-verified UNIQUE fleet-wide -- no
naming-collision precedent question, a fresh independent build.

## Decision

### Decision 1: fresh governor identity, no reuse precedent needed

`:fuel-trading-governor` is grep-verified unique across every
`blueprint.edn` in this fleet. This build follows the SAME
governed-actor architecture as every prior actor, but with its own
distinct governor identity.

### Decision 2: self-contained domain logic, direct entity booleans (no `kotoba-lang/fueltrade` to wrap, and no range-check functions to host)

Unlike `cloud-itonami-isic-4920` (freight, which delegates tracking-
number validation to a real, pre-existing `kotoba-lang/logistics`
capability library), and unlike the crude-extraction sibling (which
hosts pure physical range-check functions in its registry because its
governor re-verifies measured physical values), this fuel-wholesale
vertical needs NEITHER: there is no pre-existing fuel-trading capability
library to delegate to, AND the governor's domain checks (credit-
clearance, contract-on-file, sanctions-screening) are direct entity
boolean reads off the `fuel-order` record's own dedicated facts -- not
measured-value-vs-limit range comparisons. So `fueltrade.registry` is
RECORD CONSTRUCTION ONLY (no range-check functions), and
`fueltrade.governor` reads the order's booleans directly. No literal
code is shared with any sibling (different domain), but the
'governor re-verifies against the actor's own records before any
real-world act' discipline is the same.

### Decision 3: dual-actuation shape, SEQUENTIAL on the SAME `fuel-order` entity

Like the crude-extraction sibling's `well` entity (and the repair-shop
cluster's `ticket` shape), this vertical's `dispatch` and `settle`
actuation events apply SEQUENTIALLY to the SAME `fuel-order` -- a
bulk-fuel delivery happens first (product leaves the wholesale rack),
invoice settlement happens later (the money side of the trade, custody
/ financial transfer), on the same order record. This matches the
repair-shop / quarrying / crude-extraction clusters' sequential shape
(two real-world acts, in order, on one entity), unlike the retail
sibling's `:kind`-distinguished alternative-action shape. `high-stakes`
is `#{:delivery/dispatch :invoice/settle}`; neither ever auto-commits
at any phase.

### Decision 4: the fuel-trading checks -- direct entity booleans, documented as such

The three domain checks the governor runs on `:delivery/dispatch`
(credit-uncleared, contract-missing, and -- at both actuation ops --
counterparty-sanctions-flag-unresolved) are each a direct boolean read
off a dedicated fact on the `fuel-order` record, documented as such
rather than as measured-value range comparisons:

- `credit-uncleared` reads the dedicated `:credit-cleared?` fact and
  refuses dispatch when credit has NOT been cleared -- the leasing
  collateral-coverage discipline, applied to counterparty credit.
- `contract-missing` reads the dedicated `:contract-terms` fact and
  refuses dispatch when no contract-terms are on file -- bulk fuel never
  leaves the rack against an undocumented trade.
- `counterparty-sanctions-flag-unresolved` reads the dedicated
  `:sanctions-screened?` fact and treats an unresolved sanctions-
  screening flag as a HARD hold, evaluated UNCONDITIONALLY at both
  `:delivery/dispatch` and `:invoice/settle` -- neither product nor
  money moves against an unscreened counterparty. This reapplies the
  SAME open-flag-unresolved discipline the freight sibling's
  delivery-exception-unresolved check establishes.

Each fires when the fact is provably in its unsafe state; missing /
false reads as a violation (cannot verify safe to dispatch). No new
unconditional-evaluation ordinals are claimed: the sanctions check is a
discipline-reapplication, documented per Decision 5.

### Decision 5: `counterparty-sanctions-flag-unresolved?` -- the open-flag-unresolved discipline

An unresolved sanctions-screening flag -- the counterparty has not
passed OFAC / equivalent sanctions screening -- is a HARD,
un-overridable hold. This reuses the SAME open-flag-unresolved
discipline the freight sibling's `delivery-exception-unresolved?` check
(and the parksafety sibling's flag checks) establish -- an open concern
cannot be silently suppressed to force a delivery or invoice through.
Evaluated UNCONDITIONALLY at both `:delivery/dispatch` and
`:invoice/settle`.

### Decision 6: dedicated double-actuation-guard booleans

`:delivered?` / `:invoiced?` are dedicated booleans on the `fuel-order`
record, never a single `:status` value -- the same discipline every
prior governor's guards establish, informed by `cloud-itonami-isic-
6492`'s real status-lifecycle bug (ADR-2607071320).

### Decision 7: Store protocol, MemStore + DatomicStore parity

`fueltrade.store/Store` is implemented by both `MemStore` (atom-backed,
default for dev/tests/demo) and `DatomicStore` (`langchain.db`-backed),
proven to satisfy the same contract in
`test/fueltrade/store_contract_test.clj`. The ledger stays append-only
on every backend: which fuel-order was verified for a jurisdiction with
no official spec-basis, which counterparty had credit-uncleared / no
contract / an unresolved sanctions-screening flag, which order had bulk
fuel dispatched, which invoice was settled, on what jurisdictional
basis, approved by whom -- always a query over an immutable log.

### Decision 8: Phase 0->3 with `:delivery/dispatch`/`:invoice/settle` NEVER auto

`fueltrade.phase`'s phase table puts `:order/intake` (no direct capital
risk) in phase 3's `:auto` set as its only member; `:delivery/dispatch`
and `:invoice/settle` are deliberately ABSENT from every phase's `:auto`
set, including phase 3 -- a permanent structural fact.
`fueltrade.governor`'s high-stakes gate enforces the same invariant
independently: two layers agree that actuation is always a human
trading supervisor's call.

### Decision 9: mock + LLM advisor pair

`fueltrade.fueltradeadvisor` provides a deterministic `mock-advisor`
(default, runs offline) and an `llm-advisor` backed by a
`langchain.model/ChatModel`. The LLM advisor's EDN proposal is parsed
defensively: any parse/shape failure yields a safe low-confidence noop
so the governor escalates/holds -- an LLM hiccup can never auto-dispatch
fuel or auto-settle an invoice.

## Alternatives considered

- **Wrapping a bespoke `kotoba-lang/fueltrade` capability library.**
  Considered and explicitly ruled out: no such library exists, and
  `kotoba-lang/robotics` is generic, not fuel-trading-specific. Forcing
  a false capability-library integration would be dishonest; this build
  correctly uses self-contained domain logic instead.
- **Hosting pure range-check functions in the registry (as the crude
  sibling does).** Considered and ruled out: the fuel-trading domain
  checks are direct entity booleans (credit cleared? contract on file?
  sanctions screened?), not measured-value-vs-limit range comparisons,
  so there are no range checks to host. `fueltrade.registry` is record
  construction only.
- **A `:kind`-distinguished entity** (matching the retail sibling's
  `order` shape). Rejected: delivery and invoice settlement happen
  SEQUENTIALLY on the SAME fuel-order in this domain, not as
  alternative actions -- the repair-shop / quarrying / crude-extraction
  cluster's sequential shape is the honest match here.
- **Building tank-truck routing / trading-book optimization in this
  R0.** Rejected in favor of a scoped R0 slice (the `:optimization`
  capability is correctly marked required, the integration is a
  follow-up), consistent with this fleet's 'extending coverage is
  additive' convention.

## Consequences

- Fresh independent actor in this fleet, following the SAME
  governed-actor architecture as every prior sibling.
- Establishes the fuel-trading checks as direct entity boolean reads
  (no pure range-check functions needed), an honest structural
  differentiator from the crude-extraction sibling's registry-hosted
  physical range checks.
- `MemStore` || `DatomicStore` parity is proven by
  `test/fueltrade/store_contract_test.clj`.
- 34 tests / 164 assertions pass; lint is clean; the demo
  (`clojure -M:dev:run`) walks one clean delivery + invoice lifecycle,
  plus six HARD-hold scenarios (no spec-basis, credit-uncleared,
  contract-missing, sanctions, double delivery, double invoice),
  end-to-end.
- `blueprint.edn` required no field-sync fixes (already correct) -- only
  the `:maturity` flip itself.

## References

- `cloud-itonami-isic-6511/docs/adr/0001-architecture.md` (origin of the
  general governed-actor architecture pattern)
- `cloud-itonami-isic-4920/docs/adr/0001-architecture.md` (freight
  sibling; contrast: wraps a pre-existing `kotoba-lang/logistics`
  capability library)
- `cloud-itonami-isic-0610/docs/adr/0001-architecture.md` (crude-
  extraction sibling; contrast: hosts pure physical range-check
  functions in its registry, which this vertical does NOT need)
- `cloud-itonami-isic-0162/docs/adr/0001-architecture.md` (origin of
  the 'honest reapplication, documented as such' convention this build
  follows for its sanctions open-flag-unresolved check)
- 関税法 (Customs Act); 輸出貿易管理令 (Japan, MOF Customs / METI)
- OFAC sanctions programs; fuel excise tax, 26 U.S.C. §4081 (US,
  Treasury / IRS)
- Excise Notice 179; UK financial sanctions regulations (UK, HMRC / OFSI)
- Customs Act (Tollloven); Norway sanctions regulations (Norway,
  Customs Norway / MFA)
