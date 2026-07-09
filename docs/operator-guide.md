# Operator Guide

## First Deployment
1. Register traders, racks, fuel-orders, and trading supervisors.
2. Import fuel-order, counterparty, credit, sanctions and trade history.
3. Seed the per-jurisdiction spec-basis catalog (`fueltrade.facts`) for
   the jurisdictions you actually trade in, citing real official sources
   only.
4. Run read-only spec-basis validation per jurisdiction.
5. Configure sanctions / credit escalation and accounts-receivable
   accounts.
6. Publish a dry-run delivery/invoice and audit export.

## Minimum Trading Controls
- spec-basis validation before any verification, delivery, or invoice
- full counterparty-diligence evidence (credit-clearance record,
  contract/PO, sanctions-screening record) before any delivery
- credit-clearance, contract-on-file and sanctions-screening checks
  before any delivery; sanctions-screening before any invoice
- sanctions / credit escalation gate
- audit export for every delivery, invoice, and hold
- backup manual dispatch and invoicing process

## A Day in the Life: Intake → Verify → Dispatch → Settle → Audit

Wholesale of Solid, Liquid and Gaseous Fuels (ISIC 4671,
`cloud-itonami-isic-4671`) runs on the same intake / advise / govern /
decide / commit-or-hold loop as every itonami blueprint, but here the
loop is concrete: a regional fuel trader needs to bring a fuel-order
(say, a 50,000-barrel gasoil sale to a counterparty in Japan) from
intake through contract verification to a bulk-fuel delivery and an
invoice settlement. Walking through one order, end to end:

1. **Intake.** The trader books the fuel-order through `:forms`:
   order-id, product-grade, volume-barrels, counterparty, price,
   contract-terms, jurisdiction, and the order's own diligence record
   (credit-cleared?, sanctions-screened?). This creates a fuel-order
   record at `:order/intake` status. The FuelTradeAdvisor only
   normalizes the patch; it does not invent the order-id, counterparty,
   jurisdiction, or any commercial/diligence value.
2. **Verify.** The FuelTradeAdvisor drafts a per-jurisdiction contract /
   sanctions / fuel-excise evidence checklist (`:contract/verify`) from
   `fueltrade.facts`, citing the jurisdiction's official spec-basis
   (owner authority, legal basis, provenance) and listing the required
   evidence (credit-clearance record, contract/PO, sanctions-screening
   record). The `:fuel-trading-governor` sign-off gate must clear: it
   checks the jurisdiction actually has an official spec-basis on file
   (never invent one). A jurisdiction with no spec-basis is a HARD hold
   at the governor node -- it never even reaches a human. This
   verification always escalates to a human for approval; it is never
   auto.
3. **Dispatch.** Before bulk fuel can leave the rack, the
   `:fuel-trading-governor` sign-off gate runs the full HARD check set
   against the order's own ground truth: the spec-basis exists, the
   evidence checklist is complete, the counterparty's credit has been
   cleared, contract-terms are on file, the counterparty has passed
   sanctions screening, and the order has not already been delivered.
   Any failure is a HARD hold that a human cannot override. If every
   check is clean, the proposal STILL always escalates to a human
   trading supervisor -- a `:delivery/dispatch` never auto-commits at
   any phase. On approval, the delivery record is drafted
   (`<JURISDICTION>-DELIVERY-000001`) and the order's `:delivered?`
   flag is set.
4. **Settle.** Once bulk fuel has actually been delivered, the invoice
   is settled (`:invoice/settle`): the money side of the trade, custody
   / financial transfer. The governor re-checks the spec-basis, the
   evidence completeness, the sanctions screening, and that this order's
   invoice has not already been settled. As with the delivery, a clean
   invoice STILL always escalates to a human trading supervisor --
   `:invoice/settle` never auto-commits. On approval the invoice record
   is drafted (`<JURISDICTION>-INVOICE-000001`) and the order's
   `:invoiced?` flag is set.
5. **Audit.** The verification, the delivery sign-off, the delivery
   record, the invoice sign-off, and the invoice record are all
   appended to the `:audit-ledger` -- immutable and exportable, so a
   counterparty or regulatory dispute can be traced back to the exact
   spec-basis citation, evidence checklist, and supervisor sign-off that
   authorized the delivery and invoice. If something is wrong with the
   counterparty (a credit deterioration, a sanctions hit, a contract
   gap), that gets raised as a sanctions / credit flag and routed
   through the escalation gate instead of being silently suppressed --
   a delivery for that order then waits on governor sign-off of the
   flag's resolution.

Any deviation from this loop is exactly what the Trust Controls in
`docs/business-model.md` exist to catch: an order verified against a
fabricated spec-basis, a delivery started with incomplete evidence, an
uncleared counterparty credit or a contract gap, a sanctions
screening suppressed to force a delivery through, or an invoice posted
without a human sign-off.

## Feel the Decision Gate: `clojure -M:dev:run`

This vertical has no companion playable prototype. The fastest hands-on
way to feel why the `:fuel-trading-governor` gate exists is the bundled
demo, which walks one clean fuel-order through intake → verify →
dispatch → settle (each dispatch/settle pausing for human approval) and
then exercises every HARD-hold failure mode in isolation:

- a jurisdiction with no official spec-basis → HOLD (`:no-spec-basis`),
- a counterparty whose credit has not been cleared → HOLD
  (`:credit-uncleared`),
- an order with no contract-terms on file → HOLD (`:contract-missing`),
- a counterparty that has not passed sanctions screening → HOLD
  (`:counterparty-sanctions-flag-unresolved`),
- a double delivery of the same order → HOLD (`:already-delivered`),
- a double invoice of the same order → HOLD (`:already-invoiced`).

Each HOLD settles at the governor node and never reaches a human
approver -- the same failure mode the audit ledger is built to catch and
the minimum trading controls above are built to prevent. It is not a
substitute for those controls, but it is the fastest way for a new
operator (or a reviewer) to feel, hands-on, why the gate exists before
touching a real deployment.

## Certification
Certified operators must prove spec-basis-grounded verification,
evidence-backed delivery readiness (credit-clearance, contract-on-file,
sanctions-screening), and human review for every delivery- and
invoice-affecting action.
