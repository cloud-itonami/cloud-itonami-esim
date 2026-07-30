(ns esimprovisioning.governor-contract-test
  "The governor's contract: what it refuses HARD (no human override), what it
  escalates, and -- most importantly -- that every ground-truth fact it uses is
  recomputed from the library and the store rather than read off the proposal."
  (:require [clojure.test :refer [deftest is testing]]
            [esimprovisioning.governor :as governor]
            [esimprovisioning.store :as store]))

(def ctx {:actor-id "cloud-itonami-esim"})

(def eid store/demo-eid)
(def iccid-a store/demo-iccid-a)          ; :enabled in demo data
(def iccid-b store/demo-iccid-b)          ; :disabled in demo data
(def iccid-bad "8981012345678901231")     ; iccid-a with a tampered check digit

(defn- st [] (store/mem-store))

(defn- proposal [effect value & {:keys [confidence stake]
                                 :or {confidence 0.9}}]
  {:effect effect :value value :confidence confidence :stake stake
   :cites [:store/recorded-state] :summary "test"})

(defn- verdict [op subject p]
  (governor/check {:op op :subject subject} ctx p (st)))

(defn- rules [v] (set (mapv :rule (:violations v))))

(deftest effect-must-match-the-requested-op
  (testing "a proposal claiming a different effect than the op is a HARD refusal --
            otherwise approving a harmless-looking report could trigger a transfer"
    (let [v (verdict :coverage/report eid
                     (proposal :ownership/transfer-requested
                               {:iccid iccid-a
                                :from-subject "did:key:zDemoSubjectA"
                                :to-subject "did:key:zDemoSubjectB"}))]
      (is (:hard? v))
      (is (contains? (rules v) :effect-mismatch))))
  (testing "an op absent from the allowlist can never match, so it always refuses"
    (let [v (verdict :profile/nuke eid (proposal :profile/nuked {}))]
      (is (:hard? v))
      (is (contains? (rules v) :effect-mismatch))))
  (testing "the matching effect passes this check"
    (is (not (contains? (rules (verdict :euicc/register eid
                                        (proposal :euicc/registered {:eid eid})))
                        :effect-mismatch)))))

(deftest identifiers-are-revalidated-not-trusted
  (testing "a structurally impossible EID is a HARD refusal"
    (let [v (verdict :euicc/register "123" (proposal :euicc/registered {:eid "123"}))]
      (is (:hard? v))
      (is (contains? (rules v) :eid-invalid))))
  (testing "an ICCID failing its own E.118 check digit is a HARD refusal --
            recomputed here, never taken from the proposal's own claim"
    (let [v (verdict :profile/download eid
                     (proposal :profile/installed {:eid eid :iccid iccid-bad}))]
      (is (:hard? v))
      (is (contains? (rules v) :iccid-invalid))))
  (testing "a high self-reported confidence does not buy past a HARD violation"
    (let [v (verdict :profile/download eid
                     (proposal :profile/installed {:eid eid :iccid iccid-bad}
                               :confidence 1.0))]
      (is (:hard? v))
      (is (not (:ok? v))))))

(deftest an-unregistered-euicc-cannot-be-operated-on
  (let [v (verdict :profile/download "89049032000000000000000000000009"
                   (proposal :profile/installed
                             {:eid "89049032000000000000000000000009"
                              :iccid iccid-b}))]
    (is (:hard? v))
    (is (contains? (rules v) :euicc-unknown))))

(deftest reachability-comes-from-the-library-over-recorded-state
  (testing "enabling a second profile is refused as would-displace, distinctly
            from a plain unreachable transition, so the ledger can tell them apart"
    (let [v (verdict :profile/lifecycle eid
                     (proposal :profile/lifecycle-applied
                               {:eid eid :iccid iccid-b :operation :enable}))]
      (is (:hard? v))
      (is (contains? (rules v) :enable-would-displace))
      (is (not (contains? (rules v) :transition-unreachable)))))
  (testing "an unreachable transition is refused on the library's own reasons"
    (let [v (verdict :profile/lifecycle eid
                     (proposal :profile/lifecycle-applied
                               {:eid eid :iccid iccid-a :operation :download}))]
      (is (:hard? v))
      (is (contains? (rules v) :transition-unreachable))))
  (testing "deleting an enabled profile is refused -- the conservative reading
            lives in the library and the governor inherits it"
    (let [v (verdict :profile/lifecycle eid
                     (proposal :profile/lifecycle-applied
                               {:eid eid :iccid iccid-a :operation :delete}))]
      (is (:hard? v))
      (is (contains? (rules v) :transition-unreachable))))
  (testing "a reachable transition clears the hard checks and escalates on stake"
    (let [v (verdict :profile/lifecycle eid
                     (proposal :profile/lifecycle-applied
                               {:eid eid :iccid iccid-a :operation :disable}
                               :stake :actuation))]
      (is (not (:hard? v)))
      (is (:escalate? v))
      (is (not (:ok? v))))))

(deftest transfer-is-checked-against-the-recorded-subject
  (testing "a from-subject that does not match the record is a HARD refusal"
    (let [v (verdict :ownership/transfer eid
                     (proposal :ownership/transfer-requested
                               {:iccid iccid-a
                                :from-subject "did:key:zImposter"
                                :to-subject "did:key:zDemoSubjectB"}
                               :stake :actuation))]
      (is (:hard? v))
      (is (contains? (rules v) :transfer-from-mismatch))))
  (testing "a self-transfer is a HARD refusal -- it would record a change that
            did not happen"
    (let [v (verdict :ownership/transfer eid
                     (proposal :ownership/transfer-requested
                               {:iccid iccid-a
                                :from-subject "did:key:zDemoSubjectA"
                                :to-subject "did:key:zDemoSubjectA"}
                               :stake :actuation))]
      (is (:hard? v))
      (is (contains? (rules v) :transfer-self))))
  (testing "a well-formed transfer still never auto-passes: :actuation escalates"
    (let [v (verdict :ownership/transfer eid
                     (proposal :ownership/transfer-requested
                               {:iccid iccid-a
                                :from-subject "did:key:zDemoSubjectA"
                                :to-subject "did:key:zDemoSubjectB"}
                               :stake :actuation :confidence 1.0))]
      (is (not (:hard? v)))
      (is (:high-stakes? v))
      (is (:escalate? v))
      (is (not (:ok? v))))))

(deftest confidence-floor-escalates-without-being-hard
  (let [v (verdict :euicc/register eid
                   (proposal :euicc/registered {:eid eid} :confidence 0.1))]
    (is (not (:hard? v)))
    (is (:escalate? v))
    (is (not (:ok? v)))))

(deftest a-clean-low-stakes-proposal-is-ok
  (let [v (verdict :euicc/register eid
                   (proposal :euicc/registered {:eid eid}))]
    (is (:ok? v))
    (is (not (:hard? v)))
    (is (not (:escalate? v)))
    (is (empty? (:violations v)))))

(deftest hold-fact-carries-the-rules-it-refused-on
  (let [v (verdict :profile/download eid
                   (proposal :profile/installed {:eid eid :iccid iccid-bad}))
        f (governor/hold-fact {:op :profile/download :subject eid} ctx v)]
    (is (= :held (:t f)))
    (is (= :hold (:disposition f)))
    (is (contains? (set (:violations f)) :iccid-invalid))))
