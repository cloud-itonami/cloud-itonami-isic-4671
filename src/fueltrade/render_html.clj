(ns fueltrade.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for this repo: the console previously
  committed under `docs/samples/` was HAND-WRITTEN at the initial commit
  (no generator existed anywhere in the tree, and `git log` shows the
  file has never been regenerated). This namespace replaces it with a
  page produced by driving the REAL actor stack --
  `fueltrade.operation` (a langgraph-clj StateGraph) ->
  `fueltrade.governor` -> `fueltrade.store` -- so every value on the
  page is actual governor / store / registry output against this repo's
  real seeded fuel-orders `fo-1`..`fo-5` (`fueltrade.store/demo-data`).
  Nothing on the page is hand-typed domain data.

  The scenario is adapted from this repo's own `fueltrade.sim` demo
  driver (`clojure -M:dev:run`, run and read BEFORE this file was
  written), extended so that ALL SEVEN of the Fuel Trading Governor's
  HARD rules fire at least once, each isolated on the fuel-order whose
  seed data isolates exactly that failure mode, plus the two non-rule
  hold varieties this stack can reach (the rollout phase gate, and a
  human approver's rejection).

  Determinism: no timestamps, no randomness, no map-iteration order
  dependence (every catalog is emitted in sorted key order); two
  consecutive runs against the same seed are byte-identical.

  `-main` refuses to write a page that does not demonstrate the
  governor: it throws unless the run produced `:governor-hold` records
  AND every rule in `expected-hard-rules` (the governor's full HARD
  rule inventory) actually fired. A console that silently lost its
  holds is a worse artifact than no console.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [fueltrade.facts :as facts]
            [fueltrade.operation :as op]
            [fueltrade.phase :as phase]
            [fueltrade.store :as store]
            [langgraph.graph :as g]))

;; ----------------------------- scenario -----------------------------

(def ^:private supervisor
  "The phase-3 trading supervisor who runs the bulk of the scenario."
  {:actor-id "op-1" :actor-role :trading-supervisor :phase 3})

(def ^:private rack-operator-phase-1
  "A SECOND, deliberately less-privileged context: the same actor stack
  at rollout phase 1 (`assisted-intake`), used once to show the phase
  gate holding an op the governor itself had already cleared."
  {:actor-id "op-2" :actor-role :rack-operator :phase 1})

(defn- exec!
  "One graph run = one wholesale-fuel operation."
  [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- resume!
  "Resume a run paused at `:request-approval` with a human decision."
  [actor tid status by]
  (g/run* actor {:approval {:status status :by by}}
          {:thread-id tid :resume? true}))

(def ^:private expected-hard-rules
  "The Fuel Trading Governor's complete HARD rule inventory
  (`fueltrade.governor`: five numbered checks plus the two
  double-actuation guards). `-main` fails the build if the scenario
  stops exercising any of them -- this is the build-time invariant that
  keeps the console from quietly degrading into a happy-path page."
  #{:no-spec-basis
    :evidence-incomplete
    :credit-uncleared
    :contract-missing
    :counterparty-sanctions-flag-unresolved
    :already-delivered
    :already-invoiced})

(defn run-demo!
  "Drives a freshly seeded store through every disposition this actor
  can reach. Returns {:db store :threads [{:id .. :label .. :audit ..}]}
  -- `:audit` is the FINAL audit channel of each thread (langgraph
  restores the channel from the checkpoint on resume, so only the last
  run of a thread is collected, never both halves).

  fo-1 (Akita Energy Trading Co, JPN -- every seed field in its safe
  state) walks the full lifecycle, but is first bounced twice to prove
  the two non-domain gates:

    t01  :order/intake       phase 3, governor-clean, no capital risk
                             -> the ONLY op any phase auto-commits.
    t02  :delivery/dispatch  BEFORE any contract assessment exists
                             -> HARD hold :evidence-incomplete.
    t03  :contract/verify    -> escalate (phase 3 never auto-commits a
                             verification) -> human approves -> commit.
    t04  :delivery/dispatch  by the PHASE 1 rack operator. The governor
                             is now clean, so this hold comes from
                             `fueltrade.phase` alone (:phase-disabled)
                             -- the second, independent layer.
    t05  :delivery/dispatch  phase 3 -> escalate (permanently
                             high-stakes) -> human approves -> commit
                             -> JPN-DELIVERY-000000.
    t06  :invoice/settle     -> escalate -> human approves -> commit
                             -> JPN-INVOICE-000000.
    t07  :delivery/dispatch  again -> HARD hold :already-delivered.
    t08  :invoice/settle     again -> HARD hold :already-invoiced.

  Each remaining seeded order isolates exactly ONE failure mode:

    t09  fo-2 :contract/verify   ATL is deliberately absent from
                                 `fueltrade.facts/catalog`
                                 -> HARD hold :no-spec-basis.
    t10  fo-3 :contract/verify   approved (sets up the credit test)
    t11  fo-3 :delivery/dispatch -> HARD hold :credit-uncleared.
    t12  fo-4 :contract/verify   approved (sets up the contract test)
    t13  fo-4 :delivery/dispatch -> HARD hold :contract-missing.
    t14  fo-4 :invoice/settle    governor-CLEAN (contract-terms are not
                                 an invoice-side check) -> escalate ->
                                 the human REJECTS -> hold
                                 :approver-rejected. The one hold on
                                 this page a human produced.
    t15  fo-5 :contract/verify   approved (sets up the sanctions test)
    t16  fo-5 :delivery/dispatch -> HARD hold
                                 :counterparty-sanctions-flag-unresolved
    t17  fo-5 :invoice/settle    -> the SAME rule again, at the other
                                 actuation op: sanctions screening is
                                 evaluated UNCONDITIONALLY at both."
  []
  (let [db (store/seed-db)
        actor (op/build db)
        threads (atom [])
        record! (fn [id label result]
                  (swap! threads conj
                         {:id id :label label
                          :audit (vec (-> result :state :audit))
                          :disposition (-> result :state :disposition)})
                  result)]
    (record! "t01" "fo-1 intake (phase 3 auto-commit)"
             (exec! actor "t01" {:op :order/intake :subject "fo-1"
                                 :patch {:id "fo-1"
                                         :counterparty "Akita Energy Trading Co"}}
                    supervisor))

    (record! "t02" "fo-1 dispatch before any contract assessment"
             (exec! actor "t02" {:op :delivery/dispatch :subject "fo-1"} supervisor))

    (exec! actor "t03" {:op :contract/verify :subject "fo-1"} supervisor)
    (record! "t03" "fo-1 contract verification (approved)"
             (resume! actor "t03" :approved "op-1"))

    (record! "t04" "fo-1 dispatch attempted at rollout phase 1"
             (exec! actor "t04" {:op :delivery/dispatch :subject "fo-1"}
                    rack-operator-phase-1))

    (exec! actor "t05" {:op :delivery/dispatch :subject "fo-1"} supervisor)
    (record! "t05" "fo-1 bulk-fuel dispatch (approved)"
             (resume! actor "t05" :approved "op-1"))

    (exec! actor "t06" {:op :invoice/settle :subject "fo-1"} supervisor)
    (record! "t06" "fo-1 invoice settlement (approved)"
             (resume! actor "t06" :approved "op-1"))

    (record! "t07" "fo-1 dispatch a second time"
             (exec! actor "t07" {:op :delivery/dispatch :subject "fo-1"} supervisor))

    (record! "t08" "fo-1 invoice settled a second time"
             (exec! actor "t08" {:op :invoice/settle :subject "fo-1"} supervisor))

    (record! "t09" "fo-2 contract verification (unregistered jurisdiction ATL)"
             (exec! actor "t09" {:op :contract/verify :subject "fo-2"} supervisor))

    (exec! actor "t10" {:op :contract/verify :subject "fo-3"} supervisor)
    (record! "t10" "fo-3 contract verification (approved)"
             (resume! actor "t10" :approved "op-1"))

    (record! "t11" "fo-3 dispatch (counterparty credit not cleared)"
             (exec! actor "t11" {:op :delivery/dispatch :subject "fo-3"} supervisor))

    (exec! actor "t12" {:op :contract/verify :subject "fo-4"} supervisor)
    (record! "t12" "fo-4 contract verification (approved)"
             (resume! actor "t12" :approved "op-1"))

    (record! "t13" "fo-4 dispatch (no contract terms on file)"
             (exec! actor "t13" {:op :delivery/dispatch :subject "fo-4"} supervisor))

    (exec! actor "t14" {:op :invoice/settle :subject "fo-4"} supervisor)
    (record! "t14" "fo-4 invoice settlement (human rejected)"
             (resume! actor "t14" :rejected "op-1"))

    (exec! actor "t15" {:op :contract/verify :subject "fo-5"} supervisor)
    (record! "t15" "fo-5 contract verification (approved)"
             (resume! actor "t15" :approved "op-1"))

    (record! "t16" "fo-5 dispatch (sanctions screening not passed)"
             (exec! actor "t16" {:op :delivery/dispatch :subject "fo-5"} supervisor))

    (record! "t17" "fo-5 invoice settlement (sanctions screening not passed)"
             (exec! actor "t17" {:op :invoice/settle :subject "fo-5"} supervisor))

    {:db db :threads @threads}))

;; ----------------------------- derived readings -----------------------------

(defn- audit-facts
  "Every audit fact the scenario produced, thread by thread, in order."
  [threads]
  (vec (mapcat :audit threads)))

(defn- approval-grants
  "The `:approval-granted` facts -- who actually signed off on what.
  These live in the run's audit channel; whether they ALSO survive into
  the SSoT is measured separately by `attribution-probe`."
  [threads]
  (vec (filter #(= :approval-granted (:t %)) (audit-facts threads))))

(defn- governor-holds
  "Ledger-persisted HARD/phase holds (`:t :governor-hold`)."
  [ledger]
  (vec (filter #(= :governor-hold (:t %)) ledger)))

(defn- approval-rejections
  "Ledger-persisted human rejections (`:t :approval-rejected`)."
  [ledger]
  (vec (filter #(= :approval-rejected (:t %)) ledger)))

(defn- hard-rules-fired
  "The distinct governor RULE keywords the run actually produced.
  Phase-gate holds carry no violations, so they contribute nothing
  here -- exactly the distinction `-main` needs to assert on."
  [ledger]
  (into (sorted-set)
        (comp (mapcat :violations) (keep :rule))
        (governor-holds ledger)))

(defn- attribution-probe
  "MEASURED, not assumed. Walks each committed register and reports
  whether the approver key actually survived the commit path, so the
  page self-corrects if `fueltrade.store` is later changed.

  `fueltrade.operation` attaches the approver only to the record's
  `:payload` (`:value` is left as the advisor proposed it), so whether
  attribution survives is decided per-effect by
  `fueltrade.store/commit-record!`: the assessment branch persists
  `payload`, the fuel-order branch persists `value`, and the two
  actuation branches recompute their record from `fueltrade.registry`
  and read neither.

  The denominator is deliberately NOT the row count. A register whose
  rows were all auto-committed had no approver to keep, and reporting
  that as `0 / n` would conflate `nobody approved` with `the store did
  not keep it` -- the exact confusion this section exists to prevent.
  So the expected count is joined from the `:approval-granted` audit
  facts: how many commits into this register a human actually signed."
  [db grants]
  (let [orders (store/all-fuel-orders db)
        assessments (keep #(store/assessment-of db (:id %)) orders)
        signed (fn [op] (count (filter #(= op (:op %)) grants)))
        held (fn [rows k] (count (filter #(contains? % k) rows)))]
    [{:register "contract assessment"
      :effect :contract-assessment/set
      :op :contract/verify
      :commit-path "persists the record's :payload"
      :rows (count assessments)
      :approved (signed :contract/verify)
      :retained (held assessments :approved-by)}
     {:register "fuel-order record"
      :effect :order/upsert
      :op :order/intake
      :commit-path "persists the record's :value"
      :rows (count orders)
      :approved (signed :order/intake)
      :retained (held orders :approved-by)}
     {:register "fuel-delivery draft"
      :effect :order/mark-delivered
      :op :delivery/dispatch
      :commit-path "recomputed from fueltrade.registry"
      :rows (count (store/delivery-history db))
      :approved (signed :delivery/dispatch)
      :retained (held (store/delivery-history db) "approved_by")}
     {:register "fuel-invoice draft"
      :effect :order/mark-invoiced
      :op :invoice/settle
      :commit-path "recomputed from fueltrade.registry"
      :rows (count (store/invoice-history db))
      :approved (signed :invoice/settle)
      :retained (held (store/invoice-history db) "approved_by")}]))

(defn- evidence-state
  "Has this order's committed assessment actually satisfied its
  jurisdiction's required-evidence set? Re-evaluated here through the
  SAME `fueltrade.facts` predicate the governor uses."
  [db {:keys [id jurisdiction]}]
  (let [a (store/assessment-of db id)]
    (cond
      (nil? a) :no-assessment
      (facts/required-evidence-satisfied? jurisdiction (:checklist a)) :satisfied
      :else :incomplete)))

;; ----------------------------- html helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- pill [cls label] (str "<span class=\"pill " cls "\">" (esc label) "</span>"))
(defn- ok [label] (pill "is-ok" label))
(defn- warn [label] (pill "is-warn" label))
(defn- bad [label] (pill "is-bad" label))
(defn- info [label] (pill "is-info" label))
(defn- muted [label] (str "<span class=\"muted\">" (esc label) "</span>"))
(defn- code [v] (str "<code>" (esc v) "</code>"))
(defn- kw-name
  "Keyword -> its FULLY QUALIFIED name. `clojure.core/name` would drop
  the namespace and render `:delivery/dispatch` and any other
  `dispatch`-suffixed op identically, which makes the op column
  ambiguous."
  [v]
  (if (keyword? v) (subs (str v) 1) (str v)))

(defn- row [cells]
  (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- rows [xs] (str/join "\n" xs))

(defn- table [headers body-rows]
  (str "    <div class=\"scroll\">\n"
       "    <table>\n"
       "      <thead><tr>"
       (str/join (map #(str "<th>" (esc %) "</th>") headers))
       "</tr></thead>\n"
       "      <tbody>\n"
       (rows body-rows) "\n"
       "      </tbody>\n"
       "    </table>\n"
       "    </div>\n"))

(defn- section [title lead body]
  (str "  <section class=\"card\">\n"
       "    <h2>" title "</h2>\n"
       "    <p class=\"lead\">" lead "</p>\n"
       body
       "  </section>\n"))

;; ----------------------------- css -----------------------------

(def ^:private console-css
  "Only the jp-go-dds (デジタル庁デザインシステム) PRIMITIVES this page
  actually references, transcribed from the DADS copy this repo already
  vendors into `docs/index.html`, so the console wears the same face as
  the product page and the build stays fully offline (no git dep, no
  network fetch). Tint backgrounds use the `-50` primitive steps: the
  semantic `-1`/`-2` pairs are BOTH dark and are used for text only."
  (str
   ":root{"
   "--color-neutral-white:#ffffff;"
   "--color-neutral-solid-gray-50:#f2f2f2;"
   "--color-neutral-solid-gray-100:#e6e6e6;"
   "--color-neutral-solid-gray-200:#cccccc;"
   "--color-neutral-solid-gray-600:#666666;"
   "--color-neutral-solid-gray-700:#4d4d4d;"
   "--color-neutral-solid-gray-800:#333333;"
   "--color-neutral-solid-gray-900:#1a1a1a;"
   "--color-primitive-blue-50:#e8f1fe;"
   "--color-primitive-blue-100:#d9e6ff;"
   "--color-primitive-blue-800:#0031d8;"
   "--color-primitive-green-50:#e6f5ec;"
   "--color-primitive-green-100:#c2e5d1;"
   "--color-primitive-green-800:#197a4b;"
   "--color-primitive-orange-50:#ffeee2;"
   "--color-primitive-orange-100:#ffdfca;"
   "--color-primitive-orange-800:#c74700;"
   "--color-primitive-red-50:#fdeeee;"
   "--color-primitive-red-100:#ffdada;"
   "--color-primitive-red-800:#ec0000;"
   "--color-primitive-red-900:#ce0000;"
   "--color-semantic-success-2:var(--color-primitive-green-800);"
   "--color-semantic-error-1:var(--color-primitive-red-800);"
   "--color-semantic-error-2:var(--color-primitive-red-900);"
   "--color-semantic-warning-orange-2:var(--color-primitive-orange-800);"
   "--font-family-sans:\"Noto Sans JP\",-apple-system,BlinkMacSystemFont,sans-serif;"
   "--font-family-mono:\"Noto Sans Mono\",monospace;"
   "}\n"
   "*,*::before,*::after{box-sizing:border-box}\n"
   "body{margin:0;background:var(--color-neutral-solid-gray-50);"
   "color:var(--color-neutral-solid-gray-800);font-family:var(--font-family-sans);"
   "line-height:1.7;-webkit-text-size-adjust:100%}\n"
   "header.bar{background:var(--color-neutral-white);"
   "border-bottom:1px solid var(--color-neutral-solid-gray-200);padding:1.5rem 1.5rem 1.25rem}\n"
   "header.bar .inner{max-width:1180px;margin:0 auto}\n"
   "header.bar h1{margin:0 0 .5rem;font-size:1.5rem;line-height:1.4;font-weight:700;"
   "color:var(--color-neutral-solid-gray-900)}\n"
   "header.bar .isic{margin:0 0 .75rem;font-size:.875rem;color:var(--color-neutral-solid-gray-600)}\n"
   "header.bar .badges{display:flex;flex-wrap:wrap;gap:.5rem}\n"
   "main{max-width:1180px;margin:0 auto;padding:1.5rem 1.5rem 3rem}\n"
   ".card{background:var(--color-neutral-white);"
   "border:1px solid var(--color-neutral-solid-gray-200);border-radius:12px;"
   "padding:1.5rem;margin-bottom:1.5rem}\n"
   ".card h2{margin:0 0 .5rem;font-size:1.125rem;line-height:1.5;font-weight:700;"
   "color:var(--color-neutral-solid-gray-900)}\n"
   ".card .lead{margin:0 0 1.25rem;font-size:.875rem;line-height:1.8;"
   "color:var(--color-neutral-solid-gray-600)}\n"
   ".scroll{max-width:100%;overflow-x:auto}\n"
   "table{width:100%;border-collapse:collapse;font-size:.875rem}\n"
   "th,td{text-align:left;padding:.5rem .625rem;vertical-align:top;"
   "border-bottom:1px solid var(--color-neutral-solid-gray-100)}\n"
   "th{font-weight:700;color:var(--color-neutral-solid-gray-700);white-space:nowrap;"
   "border-bottom:2px solid var(--color-neutral-solid-gray-200)}\n"
   "tbody tr:last-child td{border-bottom:none}\n"
   "td.num{font-variant-numeric:tabular-nums;text-align:right;white-space:nowrap}\n"
   "code{font-family:var(--font-family-mono);font-size:.9em;"
   "background:var(--color-neutral-solid-gray-50);"
   "border:1px solid var(--color-neutral-solid-gray-200);border-radius:4px;padding:1px 5px}\n"
   ".muted{color:var(--color-neutral-solid-gray-600)}\n"
   ".pill{display:inline-block;border-radius:999px;padding:1px 10px;font-size:.8125rem;"
   "font-weight:700;white-space:nowrap;border:1px solid transparent}\n"
   ".is-ok{color:var(--color-semantic-success-2);background:var(--color-primitive-green-50);"
   "border-color:var(--color-primitive-green-100)}\n"
   ".is-warn{color:var(--color-semantic-warning-orange-2);background:var(--color-primitive-orange-50);"
   "border-color:var(--color-primitive-orange-100)}\n"
   ".is-bad{color:var(--color-semantic-error-2);background:var(--color-primitive-red-50);"
   "border-color:var(--color-primitive-red-100)}\n"
   ".is-info{color:var(--color-primitive-blue-800);background:var(--color-primitive-blue-50);"
   "border-color:var(--color-primitive-blue-100)}\n"
   ".note{margin:1.25rem 0 0;padding:.875rem 1rem;border-radius:8px;font-size:.8125rem;"
   "line-height:1.8;background:var(--color-primitive-blue-50);"
   "border:1px solid var(--color-primitive-blue-100);color:var(--color-neutral-solid-gray-800)}\n"
   ".note.alarm{background:var(--color-primitive-red-50);"
   "border-color:var(--color-primitive-red-100);color:var(--color-semantic-error-1)}\n"
   ".note p{margin:0}\n"
   ".note p+p{margin-top:.5rem}\n"
   "footer{max-width:1180px;margin:0 auto;padding:0 1.5rem 3rem;"
   "font-size:.8125rem;line-height:1.8;color:var(--color-neutral-solid-gray-600)}\n"
   "footer p{margin:0}\n"))

;; ----------------------------- sections -----------------------------

(defn- last-fact-for [ledger id]
  (last (filter #(= id (:subject %)) ledger)))

(defn- status-cell [ledger id]
  (let [f (last-fact-for ledger id)]
    (case (:t f)
      :committed (ok (str "committed · " (kw-name (:op f))))
      :governor-hold (let [r (or (-> f :violations first :rule) (:phase-reason f))]
                       (bad (str "HOLD · " (kw-name (or r :unknown)))))
      :approval-rejected (warn "rejected by approver")
      (muted "no activity"))))

(defn- order-row [ledger {:keys [id order-id product-grade volume-barrels counterparty
                                 jurisdiction price delivery-number invoice-number]}]
  (row [(code id)
        (esc order-id)
        (esc counterparty)
        (esc jurisdiction)
        (esc product-grade)
        (str "<span class=\"num\">" (esc volume-barrels) "</span>")
        (str "<span class=\"num\">" (esc price) "</span>")
        (if delivery-number (code delivery-number) (muted "—"))
        (if invoice-number (code invoice-number) (muted "—"))
        (status-cell ledger id)]))

(defn- diligence-row [db {:keys [id counterparty credit-cleared? contract-terms
                                 sanctions-screened? delivered? invoiced?] :as fo}]
  (row [(code id)
        (esc counterparty)
        (if (true? credit-cleared?) (ok "cleared") (bad "not cleared"))
        (if (and (some? contract-terms) (not= "" contract-terms))
          (ok (str contract-terms))
          (bad "none on file"))
        (if (true? sanctions-screened?) (ok "screened") (bad "not screened"))
        (case (evidence-state db fo)
          :satisfied (ok (str (count (facts/evidence-checklist (:jurisdiction fo)))
                              " / "
                              (count (facts/evidence-checklist (:jurisdiction fo)))
                              " on file"))
          :incomplete (bad "incomplete")
          (muted "no assessment committed"))
        (cond delivered? (ok "dispatched") :else (muted "not dispatched"))
        (cond invoiced? (ok "settled") :else (muted "not settled"))]))

(defn- hold-row [{:keys [op subject violations phase-reason phase confidence]}]
  (row [(code (kw-name op))
        (code subject)
        (if (seq violations)
          (str/join " " (map #(bad (kw-name (:rule %))) violations))
          (bad (str "phase gate · " (kw-name (or phase-reason :unknown)))))
        (if (seq violations)
          (str/join "<br>" (map #(esc (:detail %)) violations))
          (esc (str "rollout phase " phase " ("
                    (:label (get phase/phases phase)) ") does not enable this write op"
                    " -- the governor itself had already cleared it")))
        (str "<span class=\"num\">" (esc confidence) "</span>")]))

(defn- rule-inventory-rows [fired]
  (for [r (sort-by name expected-hard-rules)]
    (row [(code (name r))
          (if (contains? fired r)
            (ok "fired this run")
            (bad "NOT exercised"))])))

(defn- phase-rows []
  (for [[n {:keys [label writes auto]}] (sort-by key phase/phases)]
    (row [(str "<span class=\"num\">" n "</span>")
          (code label)
          (if (seq writes)
            (str/join " " (map #(code (kw-name %)) (sort-by kw-name writes)))
            (muted "none (read-only)"))
          (if (seq auto)
            (str/join " " (map #(code (kw-name %)) (sort-by kw-name auto)))
            (muted "none — every write needs a human"))])))

(defn- approval-row
  "One row per operation run, read out of THAT thread's own audit
  channel -- never out of a fleet-wide bag of facts, so a thread cannot
  inherit another thread's approval."
  [{:keys [id label disposition audit]}]
  (let [of (fn [t] (first (filter #(= t (:t %)) audit)))
        req (of :approval-requested)
        grant (of :approval-granted)
        reject (of :approval-rejected)]
    (row [(code id)
          (esc label)
          (if req
            (warn (str "escalated · " (kw-name (:reason req))))
            (muted "not escalated"))
          (cond
            grant (ok (str "approved by " (:by grant)))
            reject (warn "rejected by approver")
            req (muted "pending")
            :else (muted "no human involved"))
          (case disposition
            :commit (ok "commit")
            :hold (bad "hold")
            :escalate (warn "escalate")
            (muted "—"))])))

(defn- attribution-rows [probe]
  (for [{:keys [register effect commit-path rows approved retained]} probe]
    (row [(esc register)
          (code (kw-name effect))
          (esc commit-path)
          (str "<span class=\"num\">" rows "</span>")
          (str "<span class=\"num\">" approved "</span>")
          (cond
            (zero? rows) (muted "no rows committed")
            (zero? approved) (muted "no human-approved commit this run")
            (>= retained approved) (ok (str "approver retained (" retained " / " approved ")"))
            (zero? retained) (bad (str "approver DROPPED (0 / " approved ")"))
            :else (warn (str "partial (" retained " / " approved ")")))])))

(defn- ledger-row [{:keys [t op subject basis phase-reason confidence]}]
  (row [(esc (kw-name t))
        (code (kw-name (or op :n-a)))
        (code (or subject "—"))
        (cond
          (seq basis) (str/join ", " (map #(esc (kw-name %)) basis))
          phase-reason (esc (kw-name phase-reason))
          :else (muted "—"))
        (if (some? confidence)
          (str "<span class=\"num\">" (esc confidence) "</span>")
          (muted "—"))]))

(defn- book-rows [kind records]
  (for [r records]
    (row [(esc kind)
          (code (get r "record_id"))
          (code (get r "fuel_order_id"))
          (esc (get r "jurisdiction"))
          (if (get r "immutable") (info "immutable") (muted "—"))
          (if (contains? r "approved_by")
            (ok (esc (get r "approved_by")))
            (bad "not in record"))])))

(defn- catalog-rows []
  (for [[iso3 {:keys [owner-authority legal-basis provenance required-evidence]}]
        (sort-by key facts/catalog)]
    (row [(code iso3)
          (esc owner-authority)
          (esc legal-basis)
          (str "<a href=\"" (esc provenance) "\">" (esc provenance) "</a>")
          (esc (str/join "; " required-evidence))])))

;; ----------------------------- document -----------------------------

(defn render
  "Renders the whole document from a `run-demo!` result. Every cell
  below is read back out of the store, the ledger or the run's audit
  channel -- nothing is hand-typed domain data."
  [{:keys [db threads]}]
  (let [ledger (vec (store/ledger db))
        orders (store/all-fuel-orders db)
        holds (governor-holds ledger)
        rejections (approval-rejections ledger)
        grants (approval-grants threads)
        fired (hard-rules-fired ledger)
        probe (attribution-probe db grants)
        deliveries (store/delivery-history db)
        invoices (store/invoice-history db)
        cov (facts/coverage)
        hard-holds (filter #(seq (:violations %)) holds)
        phase-holds (remove #(seq (:violations %)) holds)
        attribution-gap? (boolean
                          (some (fn [{:keys [approved retained]}]
                                  (and (pos? approved) (< retained approved)))
                                probe))]
    (str
     "<!doctype html>\n"
     "<html lang=\"en\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">"
     "<meta name=\"color-scheme\" content=\"light\"><meta name=\"theme-color\" content=\"#ffffff\">"
     "<title>cloud-itonami-isic-4671 &middot; fueltrade &middot; Operator Console</title>"
     "<style>" console-css "</style></head>\n"
     "<body>\n"
     "<header class=\"bar\"><div class=\"inner\">\n"
     "  <h1>Wholesale of solid, liquid and gaseous fuels &mdash; Operator Console</h1>\n"
     "  <p class=\"isic\">ISIC 4671 &middot; <code>cloud-itonami-isic-4671</code> &middot; FuelTradeAdvisor &#8891; <code>:fuel-trading-governor</code> on a langgraph-clj StateGraph</p>\n"
     "  <div class=\"badges\">"
     (info "read-only sample")
     (info "generated at build time")
     (warn "dispatch &amp; settlement always human-approved")
     (bad (str (count hard-holds) " HARD governor holds"))
     "</div>\n"
     "</div></header>\n"
     "<main>\n"

     (section
      "Fuel-orders &mdash; SSoT after the run"
      (str "The five seeded orders of <code>fueltrade.store/demo-data</code>, read back "
           "out of the store after the scenario ran. Delivery and invoice numbers are "
           "assigned by <code>fueltrade.registry</code> from a jurisdiction-scoped "
           "sequence &mdash; an order shows one only if a human actually approved that "
           "actuation.")
      (table ["Order" "Order no." "Counterparty" "Juris." "Product grade"
              "Volume (bbl)" "Price (USD/bbl)" "Delivery no." "Invoice no." "Last decision"]
             (map (partial order-row ledger) orders)))

     (section
      "Counterparty diligence &mdash; the facts the governor actually reads"
      (str "Each seeded order isolates exactly one failure mode. These are direct entity "
           "boolean reads off the <code>fuel-order</code> record "
           "(<code>:credit-cleared?</code>, <code>:contract-terms</code>, "
           "<code>:sanctions-screened?</code>), re-evaluated here through the same "
           "<code>fueltrade.facts/required-evidence-satisfied?</code> predicate the "
           "governor uses &mdash; not a copy of the governor's opinion.")
      (table ["Order" "Counterparty" "Credit clearance" "Contract on file"
              "Sanctions screening" "Required evidence" "Delivery" "Invoice"]
             (map (partial diligence-row db) orders)))

     (section
      "Fuel Trading Governor &mdash; HARD rule inventory"
      (str "All seven HARD rules in <code>fueltrade.governor</code>. The build refuses to "
           "write this page unless every one of them fires in the scenario, so a rule that "
           "silently stops holding fails the build instead of quietly vanishing from the "
           "console.")
      (table ["Rule" "This run"] (rule-inventory-rows fired)))

     (section
      "Holds this run"
      (str (count hard-holds) " HARD governor holds (un-overridable &mdash; they never reach "
           "a human approver) and " (count phase-holds)
           " hold from the rollout phase gate, which is a second, independent layer: it can "
           "only add caution, never remove it. A HARD hold writes its rejection to the "
           "append-only ledger and mutates nothing.")
      (table ["Op" "Order" "Rule" "Detail" "Advisor confidence"]
             (map hold-row holds)))

     (section
      "Rollout phase gate (<code>fueltrade.phase</code>)"
      (str "Read directly out of <code>fueltrade.phase/phases</code>. Note that "
           "<code>:delivery/dispatch</code> and <code>:invoice/settle</code> are absent "
           "from every phase's auto set, including phase 3 &mdash; a permanent structural "
           "fact, not a rollout milestone still to come. The governor's high-stakes gate "
           "enforces the same invariant independently.")
      (table ["Phase" "Label" "Writes allowed" "May auto-commit when governor-clean"]
             (phase-rows)))

     (section
      "Approval trail"
      (str "One row per operation run. <code>interrupt-before #{:request-approval}</code> "
           "pauses the graph and hands the decision to a human trading supervisor; the "
           "approver resumes it with <code>{:approval {:status ...}}</code>. "
           (count grants) " approvals were granted and " (count rejections)
           " was rejected in this scenario.")
      (table ["Thread" "Operation" "Escalation" "Human decision" "Final disposition"]
             (map approval-row threads)))

     (section
      "Approver attribution &mdash; measured, not assumed"
      (str "<code>fueltrade.operation</code> attaches the approver to the commit record's "
           "<code>:payload</code> only; <code>:value</code> is left exactly as the advisor "
           "proposed it. Whether the approver then survives is decided per-effect by "
           "<code>fueltrade.store/commit-record!</code>. This table is derived at render "
           "time by walking each register and testing for the key, so it self-corrects if "
           "the store is later changed &mdash; it is not a hardcoded verdict.")
      (str
       (table ["Register" "Effect" "Commit path" "Rows committed"
               "Of those, human-approved" "Approver in the committed record"]
              (attribution-rows probe))
       (if attribution-gap?
         (str "    <div class=\"note alarm\"><p><strong>Attribution gap observed in this "
              "run.</strong> The assessment branch of <code>commit-record!</code> persists "
              "<code>payload</code>, so the approver survives there. The two actuation "
              "branches (<code>:order/mark-delivered</code>, "
              "<code>:order/mark-invoiced</code>) recompute their record from "
              "<code>fueltrade.registry</code> and read neither <code>payload</code> nor "
              "<code>value</code> &mdash; so for the two acts that actually move product "
              "and money, the approver is <strong>not</strong> in the book of record. "
              "(The fuel-order branch persists <code>value</code>, which would drop the "
              "approver too, but no fuel-order upsert was human-approved in this run, so "
              "it is reported as such rather than counted as a loss.)</p>"
              "<p>Who signed off is shown below from the audit facts instead, and is "
              "labelled <em>(audit only &mdash; not in commit record)</em> wherever that is "
              "the case. This page does not omit the approver: a reader must be able to tell "
              "&ldquo;nobody approved&rdquo; from &ldquo;the store did not keep it&rdquo;."
              "</p></div>\n")
         (str "    <div class=\"note\"><p>Every committed register in this run retained its "
              "approver. No audit-only fallback was needed.</p></div>\n"))))

     (section
      "Who signed off &mdash; from the audit facts"
      (str "The <code>:approval-granted</code> facts produced by the graph's "
           "<code>:request-approval</code> node. These are the authoritative record of who "
           "approved what in this run, joined here because the commit records for the two "
           "actuation effects do not carry the approver.")
      (table ["Op" "Order" "Approver" "Where this is recorded"]
             (for [{:keys [op subject by]} grants]
               (row [(code (kw-name op))
                     (code subject)
                     (ok (esc by))
                     (if (and (= op :contract/verify)
                              (contains? (or (store/assessment-of db subject) {}) :approved-by))
                       (info "commit record + audit")
                       (warn "audit only — not in commit record"))]))))

     (section
      "Book of record &mdash; fuel-delivery / fuel-invoice drafts"
      (str "Append-only registration drafts built by <code>fueltrade.registry</code> "
           "(pure functions &mdash; no call to any real loading-rack, ERP or billing "
           "system). Every certificate this actor produces is UNSIGNED: signature is the "
           "operator's act, not this actor's.")
      (table ["Kind" "Record id" "Order" "Jurisdiction" "Ledger property" "Approver in record"]
             (concat (book-rows "fuel-delivery-draft" deliveries)
                     (book-rows "fuel-invoice-draft" invoices))))

     (section
      "Audit ledger (this run)"
      (str "The append-only decision-fact log &mdash; " (count ledger)
           " facts. Every commit and every hold this scenario produced, in order. "
           "“Which order was verified on what jurisdictional basis, which counterparty "
           "had credit uncleared / no contract / an unresolved sanctions flag, which order "
           "had bulk fuel dispatched, which invoice was settled” is always a query over "
           "an immutable log.")
      (table ["Fact" "Op" "Order" "Basis" "Confidence"]
             (map ledger-row ledger)))

     (section
      "Jurisdiction spec-basis catalog (<code>fueltrade.facts</code>)"
      (str "The official-source table the governor checks every "
           "<code>:contract/verify</code> proposal against. A jurisdiction absent from this "
           "table has NO spec-basis, full stop &mdash; the advisor must not fabricate one, "
           "and the governor holds if it tries (which is exactly what happened to "
           "<code>fo-2</code> above, whose jurisdiction <code>ATL</code> is deliberately "
           "unregistered).")
      (str
       (table ["ISO3" "Owner authority" "Legal basis" "Provenance" "Required evidence"]
              (catalog-rows))
       "    <div class=\"note\"><p>" (esc (:note cov)) "</p>"
       "<p>Covered: " (esc (str/join ", " (:covered-jurisdictions cov)))
       " (" (:covered cov) " / " (:requested cov) ").</p></div>\n"))

     "</main>\n"
     "<footer><p>Generated at build time by <code>fueltrade.render-html</code> "
     "(<code>clojure -M:dev:render-html</code>) by running "
     "<code>fueltrade.operation</code> &rarr; <code>fueltrade.governor</code> &rarr; "
     "<code>fueltrade.store</code> against the seeded demo data. Deterministic: no "
     "timestamps and no randomness in the page, so two consecutive runs are "
     "byte-identical. Styling uses the jp-go-dds (デジタル庁"
     "デザインシステム) primitives this repo already "
     "vendors into <code>docs/index.html</code>.</p></footer>\n"
     "</body></html>\n")))

;; ----------------------------- entry point -----------------------------

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db] :as result} (run-demo!)
        ledger (vec (store/ledger db))
        holds (governor-holds ledger)
        hard-holds (filter #(seq (:violations %)) holds)
        fired (hard-rules-fired ledger)
        missing (into (sorted-set) (remove fired expected-hard-rules))]
    ;; Build-time invariants, not conventions. A console that cannot show
    ;; the governor refusing anything is not evidence of a governed actor,
    ;; so refuse to write the file rather than emit a happy-path page.
    (when (empty? holds)
      (throw (ex-info "render-html: the run produced ZERO :governor-hold records -- refusing to write a console that does not demonstrate the governor"
                      {:ledger-facts (count ledger)})))
    (when (empty? hard-holds)
      (throw (ex-info "render-html: no HARD governor hold (every hold came from the phase gate) -- refusing to write"
                      {:holds (count holds)})))
    (when (seq missing)
      (throw (ex-info "render-html: the scenario stopped exercising some of the governor's HARD rules -- refusing to write"
                      {:missing (vec missing) :fired (vec fired)})))
    (spit out (render result))
    (println "wrote" out
             (str "(" (count ledger) " ledger facts, "
                  (count hard-holds) " HARD governor holds over "
                  (count fired) " distinct rules, "
                  (count (remove #(seq (:violations %)) holds)) " phase-gate hold, "
                  (count (approval-rejections ledger)) " approver rejection, "
                  (count (store/delivery-history db)) " delivery draft, "
                  (count (store/invoice-history db)) " invoice draft)"))))
