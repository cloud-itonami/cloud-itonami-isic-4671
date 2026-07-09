# Business Model: Wholesale of Solid, Liquid and Gaseous Fuels

## Classification
- Repository: `cloud-itonami-isic-4671`
- ISIC Rev.5: `4671` — wholesale of solid, liquid and gaseous fuels
- Domain: `downstream/fuel-wholesale`
- Social impact: crew safety, environmental protection, transparency
- Governor: `:fuel-trading-governor`
- License: AGPL-3.0-or-later

## Scope
This actor covers fuel-order intake through per-jurisdiction contract /
sanctions / fuel-excise regulatory verification, bulk-fuel delivery
dispatch (loading real bulk fuel for a counterparty at the wholesale
rack), and invoice settlement (the money side of a wholesale-fuel
trade, custody / financial transfer) for a wholesale-fuel trader. It
does **not**, by itself, hold any wholesale-fuel licence, excise
registration or operating authority required to run a fuel-wholesale
business in a given jurisdiction, perform the actual physical tank-
truck loading, or judge trading-book economics (tank-truck routing and
trading-book optimization is a follow-up slice, not this R0). Whoever
deploys a live instance supplies the jurisdiction-specific operating
authority, the real loading-rack/valve-robot and ERP / accounts-
receivable integrations, and bears that jurisdiction's liability -- the
software supplies the governed, spec-cited, audited execution scaffold
so the operator does not have to build the compliance layer from
scratch.

## Customer
- regional and independent fuel wholesalers and rack operators
- trading houses and distributors leaving closed fuel-trading / ERP SaaS
- airport / marine / industrial bulk-fuel suppliers
- counterparties, banks and regulators who need an auditable, spec-cited
  trade record

## Offer
- fuel-order intake and directory management
- per-jurisdiction contract / sanctions / fuel-excise regulatory
  verification with an official spec-basis citation
- bulk-fuel delivery (rack dispatch) gated on full evidence, a credit-
  cleared counterparty, contract-terms on file and a passed sanctions
  screen
- invoice settlement (custody / financial transfer) with double-invoice
  prevention
- evidence checklisting (credit-clearance record, contract/PO,
  sanctions-screening record)
- sanctions and credit exception workflows
- role-based access and immutable audit ledger

## Revenue
- self-host setup fee
- managed hosting subscription per trader / rack
- support retainer with SLA
- ERP and accounts-receivable integration

## The `:fuel-trading-governor` Decision Rule

This blueprint's `:itonami.blueprint/governor` is `:fuel-trading-
governor`. It is the single authority that stands between "bulk fuel
could be dispatched to a counterparty" and "it is allowed to leave the
rack," and between "an invoice could be settled" and "it is allowed to
settle." Every rule it enforces is traceable to the domain (Wholesale
of Solid, Liquid and Gaseous Fuels, ISIC 4671) and to the three
`:social-impact` tags in `blueprint.edn` (`:safety`, `:environmental-
protection`, `:transparency`).

This is the rule the companion contract test
(`test/fueltrade/governor_contract_test.clj`) encodes end-to-end: the
FuelTradeAdvisor never dispatches bulk fuel to a counterparty or settles
an invoice the Fuel Trading Governor would reject, `:delivery/dispatch`
and `:invoice/settle` NEVER auto-commit at any phase, `:order/intake`
(no direct capital risk) MAY auto-commit when clean, and every decision
(commit OR hold) leaves exactly one ledger fact.

**Authorizes a bulk-fuel delivery (`:delivery/dispatch`) or invoice
settlement (`:invoice/settle`) only when ALL of the following hold:**

1. **An official spec-basis citation exists for the jurisdiction** -- the
   governor will not authorize any `:contract/verify`, `:delivery/
   dispatch`, or `:invoice/settle` proposal whose jurisdiction has no
   entry in the `fueltrade.facts` catalog (`:no-spec-basis`). This is the
   direct enforcement of `:transparency`: a jurisdiction whose fuel-
   wholesale / sanctions / excise requirements cannot be traced to an
   OFFICIAL public source is never guessed. The advisor must not
   fabricate a jurisdiction's requirements.
2. **The jurisdiction's required evidence is fully on file** -- for a
   delivery or invoice the order's jurisdiction must have been verified
   with a complete counterparty-diligence evidence checklist on record:
   the credit-clearance record, the contract / purchase order, and the
   sanctions-screening (OFAC / equivalent) record
   (`:evidence-incomplete`). This protects `:safety` and
   `:environmental-protection`: an order that cannot prove counterparty
   diligence never dispatches.
3. **The counterparty's credit has been cleared** -- the governor reads
   the dedicated `:credit-cleared?` fact on the order and refuses to
   dispatch bulk fuel when credit has NOT been cleared (the leasing
   collateral-coverage discipline, applied to counterparty credit)
   (`:credit-uncleared`). Evaluated at `:delivery/dispatch`.
4. **Contract-terms are on file** -- the governor refuses to dispatch
   when no `:contract-terms` are recorded for the order
   (`:contract-missing`). Bulk fuel never leaves the rack against an
   undocumented trade. Evaluated at `:delivery/dispatch`.
5. **The counterparty has passed OFAC / equivalent sanctions screening**
   -- the governor reads the dedicated `:sanctions-screened?` fact and
   treats an unresolved sanctions-screening flag as a HARD, un-
   overridable hold (`:counterparty-sanctions-flag-unresolved`). Neither
   product nor money moves against an unscreened counterparty. Evaluated
   UNCONDITIONALLY at both `:delivery/dispatch` and `:invoice/settle`.
6. **The order has not already been delivered, and the invoice has not
   already been settled** -- a double delivery of the same order is
   refused off a dedicated `:delivered?` fact, and a double invoice off a
   dedicated `:invoiced?` fact (never a `:status` value), the double-
   actuation guard every sibling actor in this fleet enforces
   (`:already-delivered` / `:already-invoiced`).

**Rejects (HOLD, un-overridable, never even reaches a human) when any of
the above fail.** A proposal with no spec-basis, incomplete evidence, an
uncleared counterparty credit, no contract-terms on file, an unresolved
sanctions-screening flag, or a double delivery/invoice is held at the
governor node -- a human approver cannot override these, by construction.

**Always escalates to a human (never auto-commits) for `:delivery/
dispatch` and `:invoice/settle`**, even when every check above is clean.
Dispatching real bulk fuel to a counterparty at the wholesale rack and
settling a real fuel invoice (real money moving between counterparty and
trader) are the two real-world actuation events this actor performs;
both are always a human trading supervisor's call. This is enforced by
TWO independent layers that agree on purpose: the governor's confidence
/ actuation SOFT gate (a `:delivery/dispatch` / `:invoice/settle` stake
always escalates) and `fueltrade.phase`'s phase table, which never puts
either op in any phase's `:auto` set. The `:environmental-protection`
tag is enforced upstream of the governor, in the contract-verification
evidence step -- the governor's job is delivery/invoice authorization
integrity, not trading-book optimization.

## Required Technologies

`blueprint.edn`'s `:itonami.blueprint/required-technologies` for this business,
and what each one is actually load-bearing for here (not a generic capability
list):

| Technology | What it is FOR in Wholesale of Solid, Liquid and Gaseous Fuels |
|---|---|
| `:robotics` | The autonomous loading-rack/valve robot that performs the physical bulk-fuel loading at the wholesale rack (and eventually the shut-off). The governor never dispatches hardware itself: a delivery-clearing action must have cleared the same sign-off a human trading supervisor would need (see Robotics Premise). |
| `:identity` | Trader, trading-supervisor, rack-operator and counterparty identity plus role-based access, so the governor's sign-off is tied to *who* authorized a delivery or invoice, not just *that* someone did. |
| `:forms` | Structured intake for fuel-order booking, per-jurisdiction evidence capture (credit-clearance record, contract/PO, sanctions-screening record), and sanctions / credit exception submission -- the data the Decision Rule above actually evaluates comes in through these forms. |
| `:dmn` | Encodes the `:fuel-trading-governor` Decision Rule itself (spec-basis, evidence completeness, credit-clearance, contract-on-file, sanctions-screening, the double-actuation guards, the actuation gate) as an evaluable decision table rather than code buried in application logic -- this is what makes the governor auditable and swappable per-deployment. |
| `:bpmn` | Orchestrates the intake -> verify -> dispatch -> settle -> audit loop end-to-end (see `docs/operator-guide.md`) across fuel-order intake, contract verification, bulk-fuel delivery, and invoice settlement, including the sanctions / credit escalation gate. |
| `:audit-ledger` | The immutable record of every verification, delivery, invoice, sanctions flag, and hold -- this is what "an auditable, spec-cited trade record for every delivery and invoice" (Trust Controls, below) actually means in practice, and the evidence an operator needs if a delivery or an invoice is later disputed by a counterparty or regulator. |
| `:optimization` | Tank-truck routing and trading-book optimization -- selects the profitable fulfillment strategy for a rack. This R0 build deliberately scopes optimization OUT (see README `Business-process coverage`); the capability is correctly marked required, the integration is a follow-up slice. |

There is NO bespoke `:fueltrade` capability library in this stack
(unlike the freight sibling's `:logistics`): the fuel-trading checks
(credit-clearance, contract-on-file, sanctions-screening) are direct
entity boolean reads in `fueltrade.governor`, on top of the generic
robotics/identity/forms/dmn/bpmn/audit-ledger stack (see Capability
layer).

## Trust Controls
- a jurisdiction with no official spec-basis can never be verified,
  dispatched, or invoiced against
- a delivery never starts with incomplete counterparty-diligence
  evidence
- a delivery never starts with an uncleared counterparty credit, no
  contract-terms on file, or an unresolved sanctions-screening flag
- an invoice never settles against an unresolved sanctions-screening flag
- sanctions / credit flags cannot be silently suppressed
- the same order can never be delivered or invoiced twice
- a delivery or invoice never auto-commits; both always need a human
  trading supervisor
- every delivery and invoice (commit OR hold) leaves exactly one
  immutable ledger fact
- counterparty, credit, sanctions and trade data stays outside Git

## Implementation notes (`:implemented`)

The Decision Rule above is implemented faithfully by `fueltrade.governor`
as seven HARD checks (a human approver cannot override them) plus one
SOFT gate:

- `spec-basis-violations` -- the spec-basis check above, evaluated on
  every `:contract/verify`, `:delivery/dispatch`, and `:invoice/settle`.
- `evidence-incomplete-violations` -- the evidence-completeness check
  above, for `:delivery/dispatch` / `:invoice/settle`.
- `credit-uncleared-violations` -- the counterparty-credit check above
  (the leasing collateral-coverage discipline applied to counterparty
  credit); evaluated on every `:delivery/dispatch`.
- `contract-missing-violations` -- the contract-on-file check above;
  evaluated on every `:delivery/dispatch`.
- `counterparty-sanctions-flag-unresolved-violations` -- the sanctions-
  screening check above (the same open-flag-unresolved discipline the
  freight sibling's delivery-exception-unresolved check establishes);
  evaluated unconditionally on both `:delivery/dispatch` and
  `:invoice/settle`.
- `already-delivered-violations` / `already-invoiced-violations` -- the
  double-actuation guards above, off dedicated `:delivered?` /
  `:invoiced?` booleans (never a `:status` value), the same discipline
  every sibling governor's guards establish.
- the confidence floor / actuation SOFT gate -- low confidence, OR a
  `:delivery/dispatch` / `:invoice/settle` stake, escalates to a human;
  and `fueltrade.phase` independently never auto-commits either op at
  any phase.

Unlike the crude-extraction sibling's governor (which calls pure
physical range-check functions in its registry), this governor needs no
range-check functions at all: its domain checks read the `fuel-order`
record's own dedicated booleans directly. `:delivery/dispatch` and
`:invoice/settle` are the two real-world actuation events
(`#{:delivery/dispatch :invoice/settle}`), applied SEQUENTIALLY to the
SAME fuel-order (delivery first, invoice settlement later) rather than
the retail sibling's `:kind`-distinguished alternative-action shape --
the same sequential dual-actuation shape the repair-shop, quarrying and
crude-extraction clusters use. Neither ever auto-commits at any phase.
Tank-truck routing and trading-book optimization (the `:optimization`
line above) is a follow-up slice, not in this R0 build -- see README
`Business-process coverage`.

## Capability layer

Unlike `cloud-itonami-isic-4920` (which wraps a pre-existing bespoke
capability library `kotoba-lang/logistics`), this vertical is
SELF-CONTAINED: there is no `kotoba-lang/fueltrade` to delegate
fuel-trading validation to. The credit-clearance / contract-on-file /
sanctions-screening checks live as direct entity boolean reads in
`fueltrade.governor` (off dedicated `:credit-cleared?` /
`:contract-terms` / `:sanctions-screened?` facts on the `fuel-order`
record) -- this vertical's governor needs no pure range-check functions
at all (contrast the crude sibling, whose registry hosts its physical
range checks), because its domain checks ARE direct boolean reads.

## Jurisdiction coverage (honest)

`fueltrade.facts/catalog` currently seeds 4 jurisdictions with an
official spec-basis, each a REAL regime: Japan (MOF Customs / METI,
関税法; 輸出貿易管理令), the United States (OFAC sanctions programs plus
the IRS fuel excise tax, 26 U.S.C. §4081), the United Kingdom (HMRC /
OFSI, Excise Notice 179; UK financial sanctions), and Norway (Customs
Norway / MFA, Customs Act; Norway sanctions regulations). This is a
starting catalog to prove the governor contract end-to-end, not a claim
of global coverage (4 of ~194 jurisdictions worldwide). Adding a
jurisdiction is additive: one map entry in `fueltrade.facts/catalog`,
citing a real official source -- never fabricate a jurisdiction's
requirements to make coverage look bigger.

## Maturity

`:implemented` -- `FuelTradeAdvisor` + `Fuel Trading Governor` run as
real, tested code (`clojure -M:dev:test`: 34 tests / 164 assertions, 0
failures; lint clean), promoted from the originally-published
`:blueprint`-tier scaffold, following the SAME governed-actor
architecture as the other prior actors across this fleet, with its own
distinct, independently-named governor and its own direct-entity-boolean
fuel-trading checks. See `docs/adr/0001-architecture.md` for the history
and design.

## Robotics Premise

`blueprint.edn` sets `:itonami.blueprint/robotics true`. In this domain
an autonomous loading-rack/valve robot performs the physical bulk-fuel
loading at the wholesale rack (and eventually the shut-off), under the
actor, gated by the independent **Fuel Trading Governor**. The governor
never dispatches hardware itself: a delivery-clearing action must have
cleared the same sign-off a human trading supervisor would need. A robot
may open the rack valve, but only after the governor (every HARD check
clean) and a human supervisor both agree it is safe to -- the same
operating-state-machine-gated-by-governor premise every cloud-itonami
vertical restates (ADR-2607011000): the blueprint declares `:robotics
true`, the README names the robot that performs the physical act, and
the Fuel Trading Governor is the independent gate that robot's command
must pass.
