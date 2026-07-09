(ns fueltrade.registry
  "Pure-function fuel-delivery + fuel-invoice record construction -- an
  append-only wholesale-fuel book-of-record draft.

  Unlike the crude-extraction sibling's own registry (which ALSO hosts
  the pure well-safety range-check functions its governor calls to
  re-verify a well's own physical ground truth before any lift), this
  fuel-wholesale vertical's Fuel Trading Governor needs NO registry
  range-check functions at all: its domain checks (credit-uncleared,
  contract-missing, counterparty-sanctions-flag-unresolved) are direct
  entity boolean reads in `fueltrade.governor`, off dedicated
  `:credit-cleared?` / `:contract-terms` / `:sanctions-screened?` facts
  on the `fuel-order` record. So this namespace is RECORD CONSTRUCTION
  ONLY -- no pure range checks to host here.

  Like every sibling actor's registry, there is no single international
  reference-number standard for a fuel-delivery or fuel-invoice record
  -- every operator/jurisdiction assigns its own reference format. This
  namespace does NOT invent one beyond a jurisdiction-scoped sequence
  number; it validates the record's required fields, the same honest,
  non-fabricating discipline `fueltrade.facts` uses.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real loading-rack/ERP/billing system. It builds the
  RECORD an operator would keep, not the act of dispatching real bulk
  fuel at the wholesale rack or settling a real fuel invoice itself
  (that is `fueltrade.operation`'s `:delivery/dispatch`/
  `:invoice/settle`, always human-gated -- see README `Actuation`)."
  (:require [clojure.string :as str]))

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is
  the operator's act, not this actor's. See README `Actuation`."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

;; ----------------------------- record construction -----------------------------

(defn register-delivery-record
  "Validate + construct the FUEL-DELIVERY registration DRAFT -- the
  operator's own legal act of dispatching real bulk fuel to a
  counterparty at the wholesale rack. Pure function -- does not touch
  any real loading-rack or ERP system; it builds the RECORD an operator
  would keep. `fueltrade.governor` independently re-verifies the
  counterparty's credit-clearance, contract-on-file, sanctions-
  screening and evidence-completeness ground truth, and blocks a
  double-delivery of the same fuel-order, before this is ever allowed
  to commit."
  [fuel-order-id jurisdiction sequence]
  (when-not (and fuel-order-id (not= fuel-order-id ""))
    (throw (ex-info "fuel-delivery: fuel_order_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "fuel-delivery: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "fuel-delivery: sequence must be >= 0" {})))
  (let [delivery-number (str (str/upper-case jurisdiction) "-DELIVERY-" (zero-pad sequence 6))
        record {"record_id" delivery-number
                "kind" "fuel-delivery-draft"
                "fuel_order_id" fuel-order-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "delivery_number" delivery-number
     "certificate" (unsigned-certificate "FuelDelivery" delivery-number delivery-number)}))

(defn register-invoice-record
  "Validate + construct the FUEL-INVOICE registration DRAFT -- the
  operator's own legal act of settling a real fuel invoice (the money
  side of a wholesale-fuel trade, custody/financial transfer). Pure
  function -- does not touch any real billing or accounts-receivable
  system; it builds the RECORD an operator would keep. `fueltrade.
  governor` independently re-verifies the sanctions-screening and
  evidence-completeness ground truth, and blocks a double-invoice of
  the same fuel-order, before this is ever allowed to commit."
  [fuel-order-id jurisdiction sequence]
  (when-not (and fuel-order-id (not= fuel-order-id ""))
    (throw (ex-info "fuel-invoice: fuel_order_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "fuel-invoice: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "fuel-invoice: sequence must be >= 0" {})))
  (let [invoice-number (str (str/upper-case jurisdiction) "-INVOICE-" (zero-pad sequence 6))
        record {"record_id" invoice-number
                "kind" "fuel-invoice-draft"
                "fuel_order_id" fuel-order-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "invoice_number" invoice-number
     "certificate" (unsigned-certificate "FuelInvoice" invoice-number invoice-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
