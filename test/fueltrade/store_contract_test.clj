(ns fueltrade.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract is
  what makes 'swap the SSoT for Datomic / kotoba-server' a
  configuration change, not a rewrite -- see `cloud-itonami-isic-6511`'s
  `underwriting.store-contract-test` for the same pattern on the
  sibling actor."
  (:require [clojure.test :refer [deftest is testing]]
            [fueltrade.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "JPN" (:jurisdiction (store/fuel-order s "fo-1"))))
      (is (= "Akita Energy Trading Co" (:counterparty (store/fuel-order s "fo-1"))))
      (is (= "Gasoil (ULSD 0.1%S)" (:product-grade (store/fuel-order s "fo-1"))))
      (is (= "ATL" (:jurisdiction (store/fuel-order s "fo-2"))))
      (is (false? (:credit-cleared? (store/fuel-order s "fo-3"))) "fo-3 credit not cleared")
      (is (nil? (:contract-terms (store/fuel-order s "fo-4"))) "fo-4 no contract-terms")
      (is (false? (:sanctions-screened? (store/fuel-order s "fo-5"))) "fo-5 sanctions not screened")
      (is (false? (:delivered? (store/fuel-order s "fo-1"))))
      (is (false? (:invoiced? (store/fuel-order s "fo-1"))))
      (is (= ["fo-1" "fo-2" "fo-3" "fo-4" "fo-5"]
             (mapv :id (store/all-fuel-orders s))))
      (is (nil? (store/assessment-of s "fo-1")))
      (is (= [] (store/ledger s)))
      (is (= [] (store/delivery-history s)))
      (is (= [] (store/invoice-history s)))
      (is (zero? (store/next-delivery-sequence s "JPN")))
      (is (zero? (store/next-invoice-sequence s "JPN")))
      (is (false? (store/fuel-order-already-delivered? s "fo-1")))
      (is (false? (store/fuel-order-already-invoiced? s "fo-1"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "partial upsert merges, preserving untouched fields"
        (store/commit-record! s {:effect :order/upsert
                                 :value {:id "fo-1" :counterparty "Akita Energy Trading Co"}})
        (is (= "Akita Energy Trading Co" (:counterparty (store/fuel-order s "fo-1"))))
        (is (= "JPN" (:jurisdiction (store/fuel-order s "fo-1"))) "unrelated field preserved"))
      (testing "contract-assessment payloads commit and read back"
        (store/commit-record! s {:effect :contract-assessment/set :path ["fo-1"]
                                 :payload {:jurisdiction "JPN" :checklist ["a" "b"]}})
        (is (= {:jurisdiction "JPN" :checklist ["a" "b"]} (store/assessment-of s "fo-1"))))
      (testing "fuel delivery drafts a record and advances the delivery sequence"
        (store/commit-record! s {:effect :order/mark-delivered :path ["fo-1"]})
        (is (= "JPN-DELIVERY-000000" (get (first (store/delivery-history s)) "record_id")))
        (is (= "fuel-delivery-draft" (get (first (store/delivery-history s)) "kind")))
        (is (true? (:delivered? (store/fuel-order s "fo-1"))))
        (is (= 1 (count (store/delivery-history s))))
        (is (= 1 (store/next-delivery-sequence s "JPN")))
        (is (true? (store/fuel-order-already-delivered? s "fo-1"))))
      (testing "invoice settlement drafts a record and advances the invoice sequence"
        (store/commit-record! s {:effect :order/mark-invoiced :path ["fo-1"]})
        (is (= "JPN-INVOICE-000000" (get (first (store/invoice-history s)) "record_id")))
        (is (= "fuel-invoice-draft" (get (first (store/invoice-history s)) "kind")))
        (is (true? (:invoiced? (store/fuel-order s "fo-1"))))
        (is (= 1 (count (store/invoice-history s))))
        (is (= 1 (store/next-invoice-sequence s "JPN")))
        (is (true? (store/fuel-order-already-invoiced? s "fo-1"))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/fuel-order s "nope")))
    (is (= [] (store/all-fuel-orders s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/delivery-history s)))
    (is (= [] (store/invoice-history s)))
    (is (zero? (store/next-delivery-sequence s "JPN")))
    (is (zero? (store/next-invoice-sequence s "JPN")))
    (store/with-fuel-orders s {"x" {:id "x" :order-id "FO-X" :product-grade "Gasoil (ULSD 0.1%S)"
                                    :volume-barrels 50000 :counterparty "c" :price 85.50
                                    :contract-terms "FOB rack, net 30 days"
                                    :credit-cleared? true :sanctions-screened? true
                                    :delivered? false :invoiced? false
                                    :jurisdiction "JPN" :status :intake
                                    :delivery-number nil :invoice-number nil}})
    (is (= "c" (:counterparty (store/fuel-order s "x"))))))
