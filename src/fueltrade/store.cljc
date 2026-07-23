(ns fueltrade.store
  "SSoT for the wholesale-fuel actor, behind a `Store` protocol so
  the backend is a swap, not a rewrite -- the same seam every prior
  `cloud-itonami-isic-*` actor in this fleet uses.

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/fueltrade/store_contract_test.clj), which is the whole point:
  the actor, the Fuel Trading Governor and the audit ledger never know
  which SSoT they run on.

  Like the crude-extraction sibling's own `well` entity (and the
  repair-shop cluster's `ticket` shape), this vertical's `dispatch` and
  `settle` actuation events apply SEQUENTIALLY to the SAME `fuel-order`
  -- a bulk-fuel delivery happens first (product leaves the wholesale
  rack), invoice settlement happens later, on the same order record.
  This matches the sequential dual-actuation shape, with dedicated
  double-actuation-guard booleans (`:delivered?`/`:invoiced?`, never a
  `:status` value).

  The ledger stays append-only on every backend: 'which fuel-order was
  verified for a jurisdiction with no official spec-basis, which
  counterparty had credit-uncleared / no contract / an unresolved
  sanctions-screening flag, which order had bulk fuel dispatched, which
  invoice was settled, on what jurisdictional basis, approved by whom'
  is always a query over an immutable log -- the audit trail a
  regulator, a counterparty, or an operator trusting a fuel-wholesale
  actor needs, and the evidence an operator needs if a delivery or an
  invoice is later disputed."
  (:require [fueltrade.registry :as registry]
            [langchain.db :as d]
            [langchain-store.core :as ls]))

(defprotocol Store
  (fuel-order [s id])
  (all-fuel-orders [s])
  (assessment-of [s fuel-order-id] "committed contract assessment, or nil")
  (ledger [s])
  (delivery-history [s] "the append-only fuel-delivery history (fueltrade.registry drafts)")
  (invoice-history [s] "the append-only fuel-invoice history (fueltrade.registry drafts)")
  (next-delivery-sequence [s jurisdiction] "next delivery-number sequence for a jurisdiction")
  (next-invoice-sequence [s jurisdiction] "next invoice-number sequence for a jurisdiction")
  (fuel-order-already-delivered? [s fuel-order-id] "has bulk fuel already been dispatched for this order?")
  (fuel-order-already-invoiced? [s fuel-order-id] "has this order's invoice already been settled?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-fuel-orders [s fuel-orders] "replace/seed the fuel-order directory (map id->fuel-order)"))

;; ----------------------------- demo data -----------------------------

(defn- base-order
  "The neutral, clean fuel-order shape (every field in its safe state),
  so each demo order below isolates exactly ONE failure mode by
  overriding a single field."
  [overrides]
  (merge {:id "fo-1" :order-id "FO-2026-0001" :product-grade "Gasoil (ULSD 0.1%S)"
          :volume-barrels 50000 :counterparty "Akita Energy Trading Co"
          :price 85.50 :contract-terms "FOB rack, net 30 days"
          :credit-cleared? true :sanctions-screened? true
          :delivered? false :invoiced? false
          :jurisdiction "JPN" :status :intake
          :delivery-number nil :invoice-number nil}
         overrides))

(defn demo-data
  "A small, self-contained fuel-order set covering both actuation
  lifecycles (delivery, invoice settlement) plus the Fuel Trading
  Governor's own checks, so the actor + tests run offline. Each
  violation order isolates exactly ONE failure mode (the rest stay
  clean) following the 'exercise the failure mode directly, never only
  via a happy-path actuation' discipline every sibling governor's demo
  data establishes."
  []
  {:fuel-orders
   (into {}
         (for [o [(base-order {:id "fo-1" :order-id "FO-2026-0001"})
                  (base-order {:id "fo-2" :order-id "FO-2026-0002"
                               :counterparty "Atlantis Trading Ltd"
                               :jurisdiction "ATL"})
                  (base-order {:id "fo-3" :order-id "FO-2026-0003"
                               :counterparty "Cedar Petroleum"
                               :credit-cleared? false})
                  (base-order {:id "fo-4" :order-id "FO-2026-0004"
                               :counterparty "Delta Fuels BV"
                               :contract-terms nil})
                  (base-order {:id "fo-5" :order-id "FO-2026-0005"
                               :counterparty "Eagle Energy SA"
                               :sanctions-screened? false})]]
           [(:id o) o]))})

;; ----------------------------- shared commit logic -----------------------------

(defn- deliver-order!
  "Backend-agnostic `:order/mark-delivered` -- looks up the fuel-order
  via the protocol and drafts the fuel-delivery record, and returns
  {:result .. :fuel-order-patch ..} for the caller to persist."
  [s fuel-order-id]
  (let [fo (fuel-order s fuel-order-id)
        seq-n (next-delivery-sequence s (:jurisdiction fo))
        result (registry/register-delivery-record fuel-order-id (:jurisdiction fo) seq-n)]
    {:result result
     :fuel-order-patch {:delivered? true
                        :delivery-number (get result "delivery_number")}}))

(defn- invoice-order!
  "Backend-agnostic `:order/mark-invoiced` -- looks up the fuel-order
  via the protocol and drafts the fuel-invoice record, and returns
  {:result .. :fuel-order-patch ..} for the caller to persist."
  [s fuel-order-id]
  (let [fo (fuel-order s fuel-order-id)
        seq-n (next-invoice-sequence s (:jurisdiction fo))
        result (registry/register-invoice-record fuel-order-id (:jurisdiction fo) seq-n)]
    {:result result
     :fuel-order-patch {:invoiced? true
                        :invoice-number (get result "invoice_number")}}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (fuel-order [_ id] (get-in @a [:fuel-orders id]))
  (all-fuel-orders [_] (sort-by :id (vals (:fuel-orders @a))))
  (assessment-of [_ fuel-order-id] (get-in @a [:assessments fuel-order-id]))
  (ledger [_] (:ledger @a))
  (delivery-history [_] (:deliveries @a))
  (invoice-history [_] (:invoices @a))
  (next-delivery-sequence [_ jurisdiction] (get-in @a [:delivery-sequences jurisdiction] 0))
  (next-invoice-sequence [_ jurisdiction] (get-in @a [:invoice-sequences jurisdiction] 0))
  (fuel-order-already-delivered? [_ fuel-order-id] (boolean (get-in @a [:fuel-orders fuel-order-id :delivered?])))
  (fuel-order-already-invoiced? [_ fuel-order-id] (boolean (get-in @a [:fuel-orders fuel-order-id :invoiced?])))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :order/upsert
      (swap! a update-in [:fuel-orders (:id value)] merge value)

      :contract-assessment/set
      (swap! a assoc-in [:assessments (first path)] payload)

      :order/mark-delivered
      (let [fuel-order-id (first path)
            {:keys [result fuel-order-patch]} (deliver-order! s fuel-order-id)
            jurisdiction (:jurisdiction (fuel-order s fuel-order-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:delivery-sequences jurisdiction] (fnil inc 0))
                       (update-in [:fuel-orders fuel-order-id] merge fuel-order-patch)
                       (update :deliveries registry/append result))))
        result)

      :order/mark-invoiced
      (let [fuel-order-id (first path)
            {:keys [result fuel-order-patch]} (invoice-order! s fuel-order-id)
            jurisdiction (:jurisdiction (fuel-order s fuel-order-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:invoice-sequences jurisdiction] (fnil inc 0))
                       (update-in [:fuel-orders fuel-order-id] merge fuel-order-patch)
                       (update :invoices registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-fuel-orders [s fuel-orders] (when (seq fuel-orders) (swap! a assoc :fuel-orders fuel-orders)) s))

(defn seed-db
  "A MemStore seeded with the demo fuel-order set. The deterministic default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :assessments {}
                           :ledger [] :delivery-sequences {} :deliveries []
                           :invoice-sequences {} :invoices []))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (assessment payloads, ledger facts, delivery/
  invoice records) are stored as EDN strings so `langchain.db`
  doesn't expand them into sub-entities -- the same convention every
  sibling actor's store uses."
  {:fuel-order/id                        {:db/unique :db.unique/identity}
   :assessment/fuel-order-id             {:db/unique :db.unique/identity}
   :ledger/seq                           {:db/unique :db.unique/identity}
   :delivery/seq                         {:db/unique :db.unique/identity}
   :invoice/seq                          {:db/unique :db.unique/identity}
   :delivery-sequence/jurisdiction       {:db/unique :db.unique/identity}
   :invoice-sequence/jurisdiction        {:db/unique :db.unique/identity}})

;; Every fuel-order field is stored as its own Datomic attr so a governor
;; pull reads the exact ground truth (no blob decode). Boolean fields
;; are coerced on read so a missing attr reads back as false (parity
;; with MemStore). [field-key tx-attr boolean?]
(def ^:private fuel-order-fields
  [[:id :fuel-order/id false]
   [:order-id :fuel-order/order-id false]
   [:product-grade :fuel-order/product-grade false]
   [:volume-barrels :fuel-order/volume-barrels false]
   [:counterparty :fuel-order/counterparty false]
   [:price :fuel-order/price false]
   [:contract-terms :fuel-order/contract-terms false]
   [:credit-cleared? :fuel-order/credit-cleared? true]
   [:sanctions-screened? :fuel-order/sanctions-screened? true]
   [:delivered? :fuel-order/delivered? true]
   [:invoiced? :fuel-order/invoiced? true]
   [:jurisdiction :fuel-order/jurisdiction false]
   [:status :fuel-order/status false]
   [:delivery-number :fuel-order/delivery-number false]
   [:invoice-number :fuel-order/invoice-number false]])

(defn- fuel-order->tx [fo]
  (reduce (fn [tx [k attr _bool?]]
            (let [v (get fo k)]
              (cond-> tx (some? v) (assoc attr v))))
          {:fuel-order/id (:id fo)}
          fuel-order-fields))

(def ^:private fuel-order-pull (mapv second fuel-order-fields))

(defn- pull->fuel-order [m]
  (when (:fuel-order/id m)
    (reduce (fn [fo [k attr bool?]]
              (let [v (get m attr)]
                (cond
                  bool?        (assoc fo k (boolean v))
                  (some? v)    (assoc fo k v)
                  :else        fo)))
            {:id (:fuel-order/id m)}
            fuel-order-fields)))

(defrecord DatomicStore [conn]
  Store
  (fuel-order [_ id]
    (pull->fuel-order (d/pull (d/db conn) fuel-order-pull [:fuel-order/id id])))
  (all-fuel-orders [_]
    (->> (d/q '[:find [?id ...] :where [?e :fuel-order/id ?id]] (d/db conn))
         (map #(pull->fuel-order (d/pull (d/db conn) fuel-order-pull [:fuel-order/id %])))
         (sort-by :id)))
  (assessment-of [_ fuel-order-id]
    (ls/dec* (d/q '[:find ?p . :in $ ?foid
                :where [?a :assessment/fuel-order-id ?foid] [?a :assessment/payload ?p]]
              (d/db conn) fuel-order-id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp ls/dec* second))))
  (delivery-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :delivery/seq ?s] [?e :delivery/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp ls/dec* second))))
  (invoice-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :invoice/seq ?s] [?e :invoice/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp ls/dec* second))))
  (next-delivery-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :delivery-sequence/jurisdiction ?j] [?e :delivery-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (next-invoice-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :invoice-sequence/jurisdiction ?j] [?e :invoice-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (fuel-order-already-delivered? [s fuel-order-id]
    (boolean (:delivered? (fuel-order s fuel-order-id))))
  (fuel-order-already-invoiced? [s fuel-order-id]
    (boolean (:invoiced? (fuel-order s fuel-order-id))))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :order/upsert
      (d/transact! conn [(fuel-order->tx value)])

      :contract-assessment/set
      (d/transact! conn [{:assessment/fuel-order-id (first path) :assessment/payload (ls/enc payload)}])

      :order/mark-delivered
      (let [fuel-order-id (first path)
            {:keys [result fuel-order-patch]} (deliver-order! s fuel-order-id)
            jurisdiction (:jurisdiction (fuel-order s fuel-order-id))
            next-n (inc (next-delivery-sequence s jurisdiction))]
        (d/transact! conn
                     [(fuel-order->tx (assoc fuel-order-patch :id fuel-order-id))
                      {:delivery-sequence/jurisdiction jurisdiction :delivery-sequence/next next-n}
                      {:delivery/seq (count (delivery-history s)) :delivery/record (ls/enc (get result "record"))}])
        result)

      :order/mark-invoiced
      (let [fuel-order-id (first path)
            {:keys [result fuel-order-patch]} (invoice-order! s fuel-order-id)
            jurisdiction (:jurisdiction (fuel-order s fuel-order-id))
            next-n (inc (next-invoice-sequence s jurisdiction))]
        (d/transact! conn
                     [(fuel-order->tx (assoc fuel-order-patch :id fuel-order-id))
                      {:invoice-sequence/jurisdiction jurisdiction :invoice-sequence/next next-n}
                      {:invoice/seq (count (invoice-history s)) :invoice/record (ls/enc (get result "record"))}])
        result)
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (ls/enc fact)}])
    fact)
  (with-fuel-orders [s fuel-orders]
    (when (seq fuel-orders) (d/transact! conn (mapv fuel-order->tx (vals fuel-orders)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:fuel-orders ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [fuel-orders]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (with-fuel-orders s fuel-orders))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo fuel-order set -- the Datomic-backed
  analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
