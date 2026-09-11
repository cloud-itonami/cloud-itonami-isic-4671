(ns fueltrade.phase
  "Phase 0->3 staged rollout for the wholesale-fuel actor.

    Phase 0  read-only        -- no writes, still governor-gated.
    Phase 1  assisted-intake  -- fuel-order intake allowed, every write
                                 needs human approval.
    Phase 2  assisted-verify  -- adds contract-verification writes,
                                 still approval.
    Phase 3  supervised auto  -- governor-clean, high-confidence
                                 `:order/intake` (no capital risk
                                 yet) may auto-commit. `:delivery/
                                 dispatch`/`:invoice/settle` NEVER
                                 auto-commit, at any phase.

  `:delivery/dispatch`/`:invoice/settle` are deliberately ABSENT from
  every phase's `:auto` set, including phase 3 -- a permanent
  structural fact, not a rollout milestone still to come. Dispatching
  real bulk fuel to a counterparty at the wholesale rack (an
  autonomous loading-rack/valve robot performs the physical bulk-fuel
  loading at the rack, or an operator does) and settling a real fuel
  invoice (real money moving between counterparty and trader) are the
  two real-world physical / financial acts this actor performs; both
  are always a human trading supervisor's call. `fueltrade.governor`'s
  `:delivery/dispatch`/`:invoice/settle` high-stakes gate enforces the
  same invariant independently -- two layers, not one, agree on this.
  Like every prior sibling's phase 3 `:auto` set, this domain has only
  ONE member (`:order/intake`) -- no separate no-capital-risk lifecycle
  distinct from the fuel-order itself.")

(def read-ops  #{})
(def write-ops #{:order/intake :contract/verify :delivery/dispatch :invoice/settle})

;; NOTE the invariant: `:delivery/dispatch`/`:invoice/settle` are members
;; of `write-ops` (governor-gated like any write) but are NEVER members
;; of any phase's `:auto` set below. Do not add them there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}."
  {0 {:label "read-only"        :writes #{}                                                :auto #{}}
   1 {:label "assisted-intake"  :writes #{:order/intake}                                    :auto #{}}
   2 {:label "assisted-verify"  :writes #{:order/intake :contract/verify}                   :auto #{}}
   3 {:label "supervised-auto"  :writes write-ops
      :auto #{:order/intake}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:delivery/dispatch`/`:invoice/settle` are never auto-eligible at
    any phase, so they always escalate once the governor clears them
    (or hold if the governor doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a Fuel Trading Governor verdict to a base disposition before the
  phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
