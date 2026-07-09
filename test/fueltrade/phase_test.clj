(ns fueltrade.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:delivery/dispatch`/`:invoice/settle` must NEVER be a
  member of any phase's `:auto` set."
  (:require [clojure.test :refer [deftest is testing]]
            [fueltrade.phase :as phase]))

(deftest delivery-dispatch-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in any future entry, auto-commits a real bulk-fuel delivery"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :delivery/dispatch))
          (str "phase " n " must not auto-commit :delivery/dispatch")))))

(deftest invoice-settle-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in any future entry, auto-commits a real fuel invoice settlement"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :invoice/settle))
          (str "phase " n " must not auto-commit :invoice/settle")))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-no-capital-risk-ops
  (testing ":order/intake carries no direct capital risk -- auto-eligible; it is the ONLY auto-eligible op in this domain"
    (is (= #{:order/intake} (:auto (get phase/phases 3))))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :order/intake} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :delivery/dispatch} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :invoice/settle} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :order/intake} :commit)))))
