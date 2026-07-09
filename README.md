# cloud-itonami-isic-4671

Open Business Blueprint for **ISIC Rev.5 4671**: Wholesale of Solid,
Liquid and Gaseous Fuels -- fuel-order intake, per-jurisdiction
counterparty-diligence / sanctions / fuel-excise regulatory
verification, bulk-fuel delivery dispatch, and invoice settlement for
a wholesale-fuel trader.

This repository publishes a fuel-wholesale actor -- fuel-order intake,
per-jurisdiction contract / sanctions / excise regulatory
verification, bulk-fuel delivery and invoice settlement -- as an OSS
business that any qualified operator can fork, deploy, run, improve
and sell, so a regional fuel trader never surrenders counterparty,
credit, sanctions and trade data to a closed fuel-trading / ERP SaaS.

Built on this workspace's
[`langgraph`](https://github.com/kotoba-lang/langgraph)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, Datomic/in-mem checkpoints) -- the same actor pattern as
every prior actor in this fleet -- here it is **FuelTradeAdvisor ⊣
Fuel Trading Governor**. This blueprint's own
`:itonami.blueprint/governor` keyword, `:fuel-trading-governor`, is a
UNIQUE keyword fleet-wide (grep-verified: no other blueprint declares
it) -- a fresh, independent build.

**Unlike `cloud-itonami-isic-4920` (which wraps a pre-existing
bespoke capability library `kotoba-lang/logistics`), this vertical is
SELF-CONTAINED**: there is no `kotoba-lang/fueltrade` to delegate
fuel-trading validation to, so the credit-clearance / contract-on-file
/ sanctions-screening checks live as direct entity boolean reads in
`fueltrade.governor` (off dedicated `:credit-cleared?` /
`:contract-terms` / `:sanctions-screened?` facts on the `fuel-order`
record), rather than wrapping an external capability library's own
validated function.

> **Why an actor layer at all?** An LLM is great at drafting an order
> summary, normalizing records, and reading a credit file -- but it
> has **no notion of which jurisdiction's fuel-wholesale / sanctions /
> fuel-excise law is official, no license to dispatch real bulk fuel
> to a counterparty or settle a real fuel invoice, and no way to know
> on its own whether the counterparty's credit has actually been
> cleared, whether contract terms are actually on file, or whether
> OFAC / equivalent sanctions screening has actually been passed**.
> Letting it dispatch fuel or settle an invoice directly invites
> fabricated regulatory citations, bulk fuel leaving the rack to an
> uncreditworthy or unscreened counterparty, and an invoice settling
> against a sanctioned party -- exposing the operator to real
> enforcement and financial liability, for whoever runs it. This
> project seals the FuelTradeAdvisor into a single node and wraps it
> with an independent **Fuel Trading Governor**, a human **approval
> workflow**, and an immutable **audit ledger**.

## Scope: what this actor does and does not do

This actor covers fuel-order intake through contract / sanctions /
fuel-excise regulatory verification, bulk-fuel delivery dispatch and
invoice settlement. It does **not**, by itself, hold any wholesale-fuel
licence, excise registration or operating authority required to run a
fuel-wholesale business in a given jurisdiction, and it does not claim
to. It also does not perform the actual physical tank-truck loading or
route optimization itself, or judge trading-book economics -- logistics
/route optimization (the blueprint's own `:optimization` technology) is
a follow-up slice, not in this R0. Whoever deploys and operates a live
instance (a qualified trading supervisor / rack operator) supplies any
jurisdiction-specific operating authority, the real loading-rack/valve-
robot dispatch integration and the real ERP / accounts-receivable
integrations, and bears that jurisdiction's liability -- the software
supplies the governed, spec-cited, audited execution scaffold so that
operator does not have to build the compliance layer from scratch.

### Actuation

**Dispatching real bulk fuel to a counterparty at the wholesale rack
and settling a real fuel invoice are never autonomous, at any phase, by
construction.** Two independent layers enforce this
(`fueltrade.governor`'s `:delivery/dispatch`/`:invoice/settle`
high-stakes gate and `fueltrade.phase`'s phase table, which never puts
either op in any phase's `:auto` set) -- see `fueltrade.phase`'s
docstring and `test/fueltrade/phase_test.clj`'s
`delivery-dispatch-never-auto-at-any-phase`/
`invoice-settle-never-auto-at-any-phase`. The actor may draft, check
and recommend; a human trading supervisor is always the one who
actually dispatches a bulk-fuel delivery or settles an invoice. Grounded
in fuel-trading doctrine (the same discipline every regulator in
`fueltrade.facts` codifies: a real delivery and a real invoice
settlement are human sign-off acts) -- a genuine DUAL-actuation shape,
applied SEQUENTIALLY to the SAME fuel-order (delivery first, invoice
settlement later), unlike `retailops`/4711's own `:kind`-distinguished
alternative-action shape.

## The core contract

```
fuel-order intake + jurisdiction facts (fueltrade.facts, spec-cited)
        |
        v
   ┌───────────────────────┐   proposal      ┌───────────────────────┐
   │ FuelTradeAdvisor      │ ─────────────▶ │ Fuel Trading Governor  │  (independent system)
   │ (sealed)              │  + citations    │ spec-basis · evidence- │
   └───────────────────────┘                 │ incomplete · credit-   │
          │                 commit ◀┼ uncleared · contract-missing ·│
          │                         │ counterparty-sanctions-flag-   │
    record + ledger        escalate ┼ unresolved · already-delivered │
          │              (ALWAYS for│ · already-invoiced             │
          │       :delivery/        └───────────────────────┘
          │       dispatch/
          │       :invoice/
          │       settle)
          ▼
      human approval
```

**The FuelTradeAdvisor never dispatches bulk fuel to a counterparty or
settles an invoice the Fuel Trading Governor would reject, and never
does so without a human sign-off.** Hard violations (fabricated
regulatory requirements; unsupported evidence; an uncleared
counterparty credit; no contract-terms on file; an unresolved
sanctions-screening flag; a double delivery/invoice) force **hold** and
*cannot* be approved past; a clean delivery/invoice proposal still
always routes to a human.

## Run

```bash
clojure -M:dev:run     # walk one clean delivery + invoice lifecycle, plus six HARD-hold cases, through the actor
clojure -M:dev:test    # governor contract · phase invariants · store parity · registry conformance · facts coverage
clojure -M:lint        # clj-kondo (errors fail; CI mirrors this)
```

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here an autonomous loading-rack /
valve robot performs the physical bulk-fuel loading at the wholesale
rack (and eventually the shut-off), under the actor, gated by the
independent **Fuel Trading Governor**. The governor never dispatches
hardware itself: a delivery-clearing action must have cleared the same
sign-off a human trading supervisor would need. This restates the
fleet-wide robotics premise three ways (ADR-2607011000): the blueprint
declares `:robotics true`, the README names the robot that performs the
physical act, and the Fuel Trading Governor is the independent gate
that robot's command must pass -- a robot may open the rack valve, but
only after the governor and a human supervisor both agree it is safe
to.

## Open business

This repository is not only source code. It is a public, forkable
business model:

| Layer | What is open |
|---|---|
| OSS core | Actor runtime, Fuel Trading Governor, delivery/invoice draft records, audit ledger |
| Business blueprint | Customer, offer, pricing, unit economics, sales motion |
| Operator playbook | How to fork, license, deploy and support the service in a jurisdiction |
| Trust controls | Governance, security reporting, actuation invariant, audit requirements |

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md) to start this as an
open business on itonami.cloud, and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for the
full architecture and decision record.

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`4671`). Unlike the freight sibling, this vertical is NOT backed by a
separate bespoke domain capability lib: the fuel-trading checks
(credit-clearance, contract-on-file, sanctions-screening) are direct
entity boolean reads in `fueltrade.governor`, on top of the generic
robotics/identity/forms/dmn/bpmn/audit-ledger stack.

## Layout

| File | Role |
|---|---|
| `src/fueltrade/store.cljc` | **Store** protocol -- `MemStore` ‖ `DatomicStore` (`langchain.db`) + append-only audit ledger + delivery AND invoice history (dual history). The double-actuation guard checks dedicated `:delivered?`/`:invoiced?` booleans rather than a `:status` value |
| `src/fueltrade/registry.cljc` | Delivery/invoice draft records (record construction only -- the Fuel Trading Governor's checks are direct entity booleans, so there are no pure range-check functions to host here, unlike the crude sibling's registry) |
| `src/fueltrade/facts.cljc` | Per-jurisdiction fuel-wholesale / sanctions / fuel-excise catalog with an official spec-basis citation per entry, honest coverage reporting |
| `src/fueltrade/fueltradeadvisor.cljc` | **FuelTradeAdvisor** -- `mock-advisor` ‖ `llm-advisor`; intake/contract-verification/delivery/invoice proposals |
| `src/fueltrade/governor.cljc` | **Fuel Trading Governor** -- 5 HARD checks (spec-basis · evidence-incomplete · credit-uncleared · contract-missing · counterparty-sanctions-flag-unresolved) + 2 double-actuation guards + 1 soft (confidence/actuation gate) |
| `src/fueltrade/phase.cljc` | **Phase 0→3** -- read-only → assisted intake → assisted verify → supervised (delivery/invoice always human; order intake is the ONLY auto-eligible op, no direct capital risk) |
| `src/fueltrade/operation.cljc` | **OperationActor** -- langgraph StateGraph |
| `src/fueltrade/sim.cljc` | demo driver |
| `test/fueltrade/*_test.clj` | governor contract · phase invariants · store parity · registry conformance · facts coverage |

## Business-process coverage (honest)

This actor covers fuel-order intake through contract / sanctions /
fuel-excise regulatory verification, bulk-fuel delivery and invoice
settlement -- the core governed lifecycle:

| Covered | Not covered (out of scope for this R0) |
|---|---|
| Fuel-order intake + per-jurisdiction evidence checklisting, HARD-gated on an official spec-basis citation (`:order/intake`/`:contract/verify`) | Real loading-rack/ERP integration, tank-truck routing and trading-book economics |
| Bulk-fuel delivery, HARD-gated on full evidence, a credit-cleared counterparty, contract-terms on file, a passed sanctions screen and no double-delivery (`:delivery/dispatch`) | |
| Invoice settlement, HARD-gated on full evidence, a passed sanctions screen and no double-invoice (`:invoice/settle`) | |
| Immutable audit ledger for every intake/verification/delivery/invoice decision | |

Extending coverage is additive: add the next gate (e.g. an excise-duty
reconciliation check) as its own governed op with its own HARD checks
and tests, following the SAME "an independent governor re-verifies
against the actor's own records before any real-world act" pattern this
repo's flagship ops already establish.

## Jurisdiction coverage (honest)

`fueltrade.facts/coverage` reports how many requested jurisdictions
actually have an official spec-basis in `fueltrade.facts/catalog` --
currently 4 seeded (JPN, USA, GBR, NOR) out of ~194 jurisdictions
worldwide. This is a starting catalog to prove the governor contract
end-to-end, not a claim of global coverage. Adding a jurisdiction is
additive: one map entry in `fueltrade.facts/catalog`, citing a real
official source -- never fabricate a jurisdiction's requirements to
make coverage look bigger.

## Maturity

`:implemented` -- `FuelTradeAdvisor` + `Fuel Trading Governor` run as
real, tested code (see `Run` above), promoted from the originally-
published `:blueprint`-tier scaffold, following the SAME governed-actor
architecture as the other prior actors across this fleet, with its own
distinct, independently-named governor and its own direct-entity-
boolean fuel-trading checks. See `docs/adr/0001-architecture.md` for
the history and design.

## License

Code and implementation templates are AGPL-3.0-or-later.
