(ns fueltrade.governor
  "Fuel Trading Governor -- the independent compliance layer that earns
  the FuelTradeAdvisor the right to commit. The LLM has no notion of
  jurisdictional fuel-wholesale / sanctions / fuel-excise law, whether
  a counterparty's credit has actually been cleared, whether contract
  terms are actually on file, whether OFAC / equivalent sanctions
  screening has actually been passed, or when an act stops being a
  draft and becomes a real bulk-fuel delivery or a real invoice
  settlement, so this MUST be a separate system able to *reject* a
  proposal and fall back to HOLD.

  Unlike the freight sibling's own governor (built on TOP of a real,
  pre-existing bespoke capability library `kotoba-lang/logistics`),
  this fuel-wholesale vertical has NO pre-existing fuel-trading
  capability library to delegate to -- so the domain checks
  (credit-clearance, contract-on-file, sanctions-screening) are direct
  entity boolean reads off the `fuel-order` record, evaluated directly
  here, NOT delegated to a separate library's validated function. (The
  crude-extraction sibling, by contrast, hosts its own pure physical
  range checks in its registry; this vertical needs no such range
  functions at all.)

  `:itonami.blueprint/governor` is `:fuel-trading-governor`, grep-
  verified UNIQUE fleet-wide -- no naming-collision precedent
  question, a fresh independent build following the SAME governed-
  actor architecture (langgraph StateGraph + independent Governor +
  Phase 0->3 rollout) established by `cloud-itonami-isic-6511`.

  Five checks, in priority order, ALL HARD violations: a human
  approver CANNOT override them. The confidence/actuation gate is
  SOFT: it asks a human to look (low confidence / actuation), and the
  human may approve -- but see `fueltrade.phase`: for `:stake
  :delivery/dispatch`/`:invoice/settle` (a real delivery or invoice
  settlement) NO phase ever allows auto-commit either. Two independent
  layers agree that actuation is always a human call.

    1. Spec-basis                  -- did the jurisdiction proposal cite
                                       an OFFICIAL source
                                       (`fueltrade.facts`), or invent one?
    2. Evidence incomplete         -- for `:delivery/dispatch`/
                                       `:invoice/settle`, has the
                                       jurisdiction actually been
                                       verified with a full counterparty-
                                       diligence evidence checklist on
                                       file?
    3. Credit uncleared            -- for `:delivery/dispatch`, the
                                       counterparty's credit has NOT been
                                       cleared (the leasing collateral-
                                       coverage discipline, applied to
                                       counterparty credit). Evaluated at
                                       the rack.
    4. Contract missing            -- for `:delivery/dispatch`, no
                                       contract-terms are on file for the
                                       order. Evaluated at the rack.
    5. Counterparty sanctions flag
       unresolved                  -- for `:delivery/dispatch` and
                                       `:invoice/settle`, the counterparty
                                       has NOT passed OFAC / equivalent
                                       sanctions screening -- a HARD,
                                       un-overridable hold. Evaluated
                                       UNCONDITIONALLY at both actuation
                                       ops.
    6. Confidence floor / actuation
       gate                          -- LLM confidence below threshold,
                                       OR the op is `:delivery/dispatch`/
                                       `:invoice/settle` (REAL acts)
                                       -> escalate.

  Two more guards, double-delivery/double-invoice prevention, are
  enforced but NOT listed as numbered HARD checks above because they
  need no upstream comparison at all -- `already-delivered-violations`/
  `already-invoiced-violations` refuse to dispatch/invoice the SAME
  fuel-order twice, off dedicated `:delivered?`/`:invoiced?` facts
  (never a `:status` value) -- the SAME 'check a dedicated boolean,
  not status' discipline every prior governor's guards establish,
  informed by `cloud-itonami-isic-6492`'s status-lifecycle bug
  (ADR-2607071320)."
  (:require [fueltrade.facts :as facts]
            [fueltrade.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Dispatching a real bulk-fuel delivery to a counterparty at the
  wholesale rack and settling a real fuel invoice (real money moving
  between counterparty and trader) are the two real-world actuation
  events this actor performs -- a two-member set, matching every
  sibling's own dual-actuation shape."
  #{:delivery/dispatch :invoice/settle})

;; ----------------------------- checks -----------------------------

(defn- spec-basis-violations
  "A `:contract/verify` (or `:delivery/dispatch`/`:invoice/settle`)
  proposal with no spec-basis citation is a HARD violation -- never
  invent a jurisdiction's fuel-wholesale / sanctions / excise
  requirements."
  [{:keys [op]} proposal]
  (when (contains? #{:contract/verify :delivery/dispatch :invoice/settle} op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-spec-basis
          :detail "公式spec-basisの引用が無い提案は法域要件として扱えない"}]))))

(defn- evidence-incomplete-violations
  "For `:delivery/dispatch`/`:invoice/settle`, the jurisdiction's
  required counterparty-diligence evidence (credit-clearance record,
  contract/PO, sanctions-screening record) must actually be satisfied
  -- do not trust the advisor's self-reported confidence alone."
  [{:keys [op subject]} st]
  (when (contains? #{:delivery/dispatch :invoice/settle} op)
    (let [fo (store/fuel-order st subject)
          assessment (store/assessment-of st subject)]
      (when-not (and assessment
                     (facts/required-evidence-satisfied?
                      (:jurisdiction fo) (:checklist assessment)))
        [{:rule :evidence-incomplete
          :detail "法域の必要書類(信用審査記録/契約書またはPO/制裁スクリーニング記録)が充足していない状態での提案"}]))))

(defn- credit-uncleared-violations
  "For `:delivery/dispatch`, refuses to dispatch bulk fuel to a
  counterparty whose credit has NOT been cleared -- counterparty credit
  not cleared (the leasing collateral-coverage discipline, applied to
  counterparty credit). Evaluated at the rack, ahead of any physical
  loading."
  [{:keys [op subject]} st]
  (when (= op :delivery/dispatch)
    (let [fo (store/fuel-order st subject)]
      (when (not (true? (:credit-cleared? fo)))
        [{:rule :credit-uncleared
          :detail (str subject " の取引先信用審査(credit-clearance)が未了 -- 出荷提案は進められない")}]))))

(defn- contract-missing-violations
  "For `:delivery/dispatch`, refuses to dispatch bulk fuel when no
  contract-terms are on file for the order."
  [{:keys [op subject]} st]
  (when (= op :delivery/dispatch)
    (let [fo (store/fuel-order st subject)]
      (when (or (nil? (:contract-terms fo)) (= "" (:contract-terms fo)))
        [{:rule :contract-missing
          :detail (str subject " に契約条項(contract-terms)の記録が無い -- 出荷提案は進められない")}]))))

(defn- counterparty-sanctions-flag-unresolved-violations
  "For `:delivery/dispatch` and `:invoice/settle`, an unresolved
  sanctions-screening flag -- the counterparty has NOT passed OFAC /
  equivalent sanctions screening -- is a HARD, un-overridable hold.
  Evaluated UNCONDITIONALLY at both actuation ops: neither product
  leaves the rack nor money settles against an unscreened
  counterparty."
  [{:keys [op subject]} st]
  (when (contains? #{:delivery/dispatch :invoice/settle} op)
    (let [fo (store/fuel-order st subject)]
      (when (not (true? (:sanctions-screened? fo)))
        [{:rule :counterparty-sanctions-flag-unresolved
          :detail (str subject " の取引先制裁スクリーニング(OFAC等)が未了 -- 出荷・請求提案は進められない")}]))))

(defn- already-delivered-violations
  "For `:delivery/dispatch`, refuses to dispatch the SAME fuel-order
  twice, off a dedicated `:delivered?` fact (never a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :delivery/dispatch)
    (when (store/fuel-order-already-delivered? st subject)
      [{:rule :already-delivered
        :detail (str subject " は既に出荷済み")}])))

(defn- already-invoiced-violations
  "For `:invoice/settle`, refuses to settle the SAME fuel-order's
  invoice twice, off a dedicated `:invoiced?` fact (never a `:status`
  value)."
  [{:keys [op subject]} st]
  (when (= op :invoice/settle)
    (when (store/fuel-order-already-invoiced? st subject)
      [{:rule :already-invoiced
        :detail (str subject " は既に請求済み")}])))

(defn check
  "Censors a FuelTradeAdvisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (spec-basis-violations request proposal)
                           (evidence-incomplete-violations request st)
                           (credit-uncleared-violations request st)
                           (contract-missing-violations request st)
                           (counterparty-sanctions-flag-unresolved-violations request st)
                           (already-delivered-violations request st)
                           (already-invoiced-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
