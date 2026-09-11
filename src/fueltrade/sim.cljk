(ns fueltrade.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean fuel-order
  through intake -> contract verification -> bulk-fuel delivery
  (escalate/approve/commit) -> invoice settlement (escalate/approve/
  commit), then shows HARD-hold scenarios: a jurisdiction with no
  spec-basis, a counterparty whose credit has not been cleared, an
  order with no contract-terms on file, a counterparty that has not
  passed sanctions screening, a double delivery, and a double invoice.

  Like every sibling actor's domain checks, this actor's checks
  (`credit-uncleared`, `contract-missing`,
  `counterparty-sanctions-flag-unresolved`) are evaluated directly at
  `:delivery/dispatch` (and sanctions at `:invoice/settle` too) rather
  than via a separate screening op -- a real delivery decision
  validates counterparty credit, contract-on-file and sanctions
  screening at the point of the act itself, not as a discrete pre-
  screening ceremony. Each check is still exercised directly and
  independently below, one order per HARD-hold scenario, following the
  SAME 'exercise the failure mode directly, never only via a happy-path
  actuation' discipline `parksafety`'s ADR-2607071922 Decision 5 and
  every sibling since establish."
  (:require [langgraph.graph :as g]
            [fueltrade.store :as store]
            [fueltrade.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :trading-supervisor :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== order/intake fo-1 (JPN, clean) ==")
    (println (exec-op actor "t1" {:op :order/intake :subject "fo-1"
                                  :patch {:id "fo-1" :counterparty "Akita Energy Trading Co"}} operator))

    (println "== contract/verify fo-1 (escalates -- human approves) ==")
    (println (exec-op actor "t2" {:op :contract/verify :subject "fo-1"} operator))
    (println (approve! actor "t2"))

    (println "== delivery/dispatch fo-1 (always escalates -- :delivery/dispatch) ==")
    (let [r (exec-op actor "t3" {:op :delivery/dispatch :subject "fo-1"} operator)]
      (println r)
      (println "-- human trading supervisor approves --")
      (println (approve! actor "t3")))

    (println "== invoice/settle fo-1 (always escalates -- :invoice/settle) ==")
    (let [r (exec-op actor "t4" {:op :invoice/settle :subject "fo-1"} operator)]
      (println r)
      (println "-- human trading supervisor approves --")
      (println (approve! actor "t4")))

    (println "== contract/verify fo-2 (no spec-basis -> HARD hold) ==")
    (println (exec-op actor "t5" {:op :contract/verify :subject "fo-2"} operator))

    (println "== contract/verify fo-3 (escalates -- human approves; sets up the credit-uncleared test) ==")
    (println (exec-op actor "t6" {:op :contract/verify :subject "fo-3"} operator))
    (println (approve! actor "t6"))

    (println "== delivery/dispatch fo-3 (credit not cleared -> HARD hold) ==")
    (println (exec-op actor "t7" {:op :delivery/dispatch :subject "fo-3"} operator))

    (println "== contract/verify fo-4 (escalates -- human approves; sets up the contract-missing test) ==")
    (println (exec-op actor "t8" {:op :contract/verify :subject "fo-4"} operator))
    (println (approve! actor "t8"))

    (println "== delivery/dispatch fo-4 (no contract-terms on file -> HARD hold) ==")
    (println (exec-op actor "t9" {:op :delivery/dispatch :subject "fo-4"} operator))

    (println "== contract/verify fo-5 (escalates -- human approves; sets up the sanctions test) ==")
    (println (exec-op actor "t10" {:op :contract/verify :subject "fo-5"} operator))
    (println (approve! actor "t10"))

    (println "== delivery/dispatch fo-5 (sanctions screening not passed -> HARD hold) ==")
    (println (exec-op actor "t11" {:op :delivery/dispatch :subject "fo-5"} operator))

    (println "== delivery/dispatch fo-1 AGAIN (double-delivery -> HARD hold) ==")
    (println (exec-op actor "t12" {:op :delivery/dispatch :subject "fo-1"} operator))

    (println "== invoice/settle fo-1 AGAIN (double-invoice -> HARD hold) ==")
    (println (exec-op actor "t13" {:op :invoice/settle :subject "fo-1"} operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft fuel-delivery records ==")
    (doseq [r (store/delivery-history db)] (println r))

    (println "== draft fuel-invoice records ==")
    (doseq [r (store/invoice-history db)] (println r))))
