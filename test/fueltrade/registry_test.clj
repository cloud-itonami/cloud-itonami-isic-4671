(ns fueltrade.registry-test
  (:require [clojure.test :refer [deftest is]]
            [fueltrade.registry :as r]))

;; The fuel-trading domain checks (credit-clearance, contract-on-file,
;; sanctions-screening) are direct entity booleans in the governor, NOT
;; pure registry range functions -- so this registry has NO range-check
;; suite to test (unlike the crude-extraction sibling's reservoir/
;; annular/water-cut/H2S functions). Only record construction is here.

;; ----------------------------- register-delivery-record -----------------------------

(deftest delivery-is-a-draft-not-a-real-delivery
  (let [result (r/register-delivery-record "fo-1" "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest delivery-assigns-delivery-number
  (let [result (r/register-delivery-record "fo-1" "JPN" 7)]
    (is (= (get result "delivery_number") "JPN-DELIVERY-000007"))
    (is (= (get-in result ["record" "fuel_order_id"]) "fo-1"))
    (is (= (get-in result ["record" "kind"]) "fuel-delivery-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest delivery-validation-rules
  (is (thrown? Exception (r/register-delivery-record "" "JPN" 0)))
  (is (thrown? Exception (r/register-delivery-record "fo-1" "" 0)))
  (is (thrown? Exception (r/register-delivery-record "fo-1" "JPN" -1))))

;; ----------------------------- register-invoice-record -----------------------------

(deftest invoice-is-a-draft-not-a-real-invoice
  (let [result (r/register-invoice-record "fo-1" "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest invoice-assigns-invoice-number
  (let [result (r/register-invoice-record "fo-1" "JPN" 7)]
    (is (= (get result "invoice_number") "JPN-INVOICE-000007"))
    (is (= (get-in result ["record" "fuel_order_id"]) "fo-1"))
    (is (= (get-in result ["record" "kind"]) "fuel-invoice-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest invoice-validation-rules
  (is (thrown? Exception (r/register-invoice-record "" "JPN" 0)))
  (is (thrown? Exception (r/register-invoice-record "fo-1" "" 0)))
  (is (thrown? Exception (r/register-invoice-record "fo-1" "JPN" -1))))

(deftest history-is-append-only
  (let [c1 (r/register-delivery-record "fo-1" "JPN" 0)
        hist (r/append [] c1)
        c2 (r/register-delivery-record "fo-2" "JPN" 1)
        hist2 (r/append hist c2)]
    (is (= 2 (count hist2)))
    (is (= "JPN-DELIVERY-000000" (get-in hist2 [0 "record_id"])))
    (is (= "JPN-DELIVERY-000001" (get-in hist2 [1 "record_id"])))))
