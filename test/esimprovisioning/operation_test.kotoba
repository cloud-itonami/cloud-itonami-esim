(ns esimprovisioning.operation-test
  "End-to-end through the compiled StateGraph: the single invariant is that the
  actor never writes what the governor refuses, and that an actuation only lands
  after a real human approval that the ledger can name."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [esimprovisioning.operation :as operation]
            [esimprovisioning.store :as store]))

(def ctx {:actor-id "cloud-itonami-esim"})
(def eid store/demo-eid)
(def iccid-a store/demo-iccid-a)
(def iccid-b store/demo-iccid-b)
(def iccid-bad "8981012345678901231")

(defn- fresh []
  (let [st (store/mem-store)]
    [st (operation/build st)]))

(defn- run
  ([app request] (run app request nil))
  ([app request approval]
   ;; Resuming through interrupt-before requires :resume? true (or a nil
     ;; input) -- see langgraph.graph/run*. Passing the prior state as a plain
     ;; input starts a FRESH run instead, which silently re-advises and
     ;; re-escalates rather than continuing past the human gate.
     (let [cfg {:thread-id (str (hash [request approval]))}
         s1 (g/invoke app {:request request :context ctx} cfg)]
     (if (and approval (= :escalate (:disposition s1)))
       (g/invoke app {:approval approval} (assoc cfg :resume? true))
       s1))))

(deftest a-clean-registration-auto-commits-at-phase-3
  (let [[st app] (fresh)
        new-eid "89049032000000000000000000000002"
        s (run app {:op :euicc/register :subject new-eid :value {:eid new-eid}})]
    (is (= :commit (:disposition s)))
    (is (some? (store/euicc st new-eid)) "the eUICC must actually be recorded")
    (testing "and the ledger names the commit"
      (is (= [:advised :committed] (mapv :t (store/ledger st)))))))

(deftest a-hard-refusal-writes-nothing-to-the-ssot
  (let [[st app] (fresh)
        before (store/profiles-of st eid)
        s (run app {:op :profile/download :subject eid
                    :value {:eid eid :iccid iccid-bad}})]
    (is (= :hold (:disposition s)))
    (is (= before (store/profiles-of st eid)) "no profile may be installed")
    (testing "but the refusal IS in the ledger, with its rule"
      (let [held (first (filter #(= :held (:t %)) (store/ledger st)))]
        (is (some? held))
        (is (contains? (set (:violations held)) :iccid-invalid))))))

(deftest an-approver-cannot-override-a-hard-violation
  (testing "approving does not even get offered -- a hard verdict holds before
            the approval node is reached"
    (let [[st app] (fresh)
          s (run app {:op :profile/lifecycle :subject eid
                      :value {:eid eid :iccid iccid-b :operation :enable}}
                 {:status :approved :by "operator@example"})]
      (is (= :hold (:disposition s)))
      (is (= iccid-a (store/enabled-iccid st eid))
          "the incumbent enabled profile must be untouched")
      (is (nil? (first (filter #(= :committed (:t %)) (store/ledger st))))))))

(deftest an-actuation-escalates-and-only-commits-once-approved
  (let [[st app] (fresh)
        request {:op :profile/lifecycle :subject eid
                 :value {:eid eid :iccid iccid-a :operation :disable}}]
    (testing "without an approval it stops at the interrupt"
      (let [s (run app request)]
        (is (= :escalate (:disposition s)))
        (is (= :enabled (:esim/state (store/profile-of st eid iccid-a)))
            "nothing may change while the human has not answered")))
    (testing "with an approval it commits and the ledger names the approver"
      (let [[st2 app2] (fresh)
            s (run app2 request {:status :approved :by "operator@example"})]
        (is (= :commit (:disposition s)))
        (is (= :disabled (:esim/state (store/profile-of st2 eid iccid-a))))
        (is (nil? (store/enabled-iccid st2 eid)))
        (let [c (first (filter #(= :committed (:t %)) (store/ledger st2)))]
          (is (= "operator@example" (:approved-by c))))))))

(deftest a-rejected-approval-holds-and-is-recorded
  (let [[st app] (fresh)
        s (run app {:op :profile/lifecycle :subject eid
                    :value {:eid eid :iccid iccid-a :operation :disable}}
               {:status :rejected :by "operator@example"})]
    (is (= :hold (:disposition s)))
    (is (= :enabled (:esim/state (store/profile-of st eid iccid-a))))
    (let [r (first (filter #(= :approval-rejected (:t %)) (store/ledger st)))]
      (is (some? r))
      (is (= "operator@example" (:by r))))))

(deftest an-ownership-transfer-is-recorded-as-a-request-never-executed
  (let [[st app] (fresh)
        s (run app {:op :ownership/transfer :subject eid
                    :value {:iccid iccid-a
                            :from-subject "did:key:zDemoSubjectA"
                            :to-subject "did:key:zDemoSubjectB"}}
               {:status :approved :by "operator@example"})]
    (is (= :commit (:disposition s)))
    (testing "the transfer record exists but is explicitly not executed"
      (let [t (store/transfer-of st iccid-a)]
        (is (some? t))
        (is (false? (:executed t)))))
    (testing "and the subject of record has NOT moved -- execution is a licensed
              operator act outside this actor"
      (is (= "did:key:zDemoSubjectA" (store/subject-of st iccid-a))))))

(deftest the-ledger-is-append-only-across-runs
  (let [[st app] (fresh)]
    (run app {:op :euicc/register :subject "89049032000000000000000000000002"
              :value {:eid "89049032000000000000000000000002"}})
    (let [n (count (store/ledger st))]
      (run app {:op :profile/download :subject eid
                :value {:eid eid :iccid iccid-bad}})
      (is (> (count (store/ledger st)) n)
          "later facts are appended, never replacing earlier ones")
      (is (= :advised (:t (first (store/ledger st))))
          "the first fact stays first"))))
