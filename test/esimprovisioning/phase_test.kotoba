(ns esimprovisioning.phase-test
  (:require [clojure.test :refer [deftest is testing]]
            [esimprovisioning.phase :as phase]))

(defn- gate [ph op disposition]
  (phase/gate ph {:op op} disposition))

(deftest a-governor-hold-always-stays-a-hold
  (testing "the phase can only add caution, never remove it"
    (doseq [ph (keys phase/phases)
            op phase/write-ops]
      (is (= :hold (:disposition (gate ph op :hold)))
          (str "phase " ph " / " op)))))

(deftest reads-pass-through-at-every-phase
  (doseq [ph (keys phase/phases)]
    (is (= :commit (:disposition (gate ph :coverage/report :commit))))))

(deftest actuation-ops-never-auto-commit-at-any-phase
  (testing "this is a structural fact about the table, not a rollout milestone"
    (doseq [ph (keys phase/phases)
            op [:profile/download :profile/lifecycle :ownership/transfer]]
      (is (not (contains? (get-in phase/phases [ph :auto]) op))
          (str op " must never be in phase " ph "'s :auto set"))))
  (testing "so a governor-clean actuation op escalates rather than committing"
    (doseq [op [:profile/download :profile/lifecycle :ownership/transfer]]
      (is (= :escalate (:disposition (gate 3 op :commit)))
          (str op " at phase 3 must escalate")))))

(deftest phase-0-writes-nothing
  (doseq [op phase/write-ops]
    (let [{:keys [disposition reason]} (gate 0 op :commit)]
      (is (= :hold disposition))
      (is (= :phase-disabled reason)))))

(deftest phase-1-admits-registration-but-needs-approval
  (let [{:keys [disposition reason]} (gate 1 :euicc/register :commit)]
    (is (= :escalate disposition))
    (is (= :phase-approval reason)))
  (testing "and still refuses the rest"
    (is (= :hold (:disposition (gate 1 :smds/register :commit))))
    (is (= :hold (:disposition (gate 1 :profile/download :commit))))))

(deftest phase-2-adds-discovery-still-with-approval
  (is (= :escalate (:disposition (gate 2 :smds/register :commit))))
  (is (= :hold (:disposition (gate 2 :profile/lifecycle :commit)))))

(deftest phase-3-auto-commits-only-registration
  (is (= :commit (:disposition (gate 3 :euicc/register :commit))))
  (is (= :escalate (:disposition (gate 3 :smds/register :commit))))
  (is (= #{:euicc/register} (get-in phase/phases [3 :auto]))))

(deftest an-unknown-phase-falls-back-to-the-default-not-to-permissive
  (let [{:keys [disposition]} (gate 99 :profile/lifecycle :commit)]
    (is (= :escalate disposition)))
  (testing "and an unknown op is not writable in any phase"
    (doseq [ph (keys phase/phases)]
      (is (= :hold (:disposition (gate ph :profile/nuke :commit)))))))

(deftest verdict-mapping
  (is (= :hold (phase/verdict->disposition {:hard? true})))
  (is (= :escalate (phase/verdict->disposition {:escalate? true})))
  (is (= :commit (phase/verdict->disposition {})))
  (testing "hard wins over escalate"
    (is (= :hold (phase/verdict->disposition {:hard? true :escalate? true})))))
