(ns esimprovisioning.store-contract-test
  "What the store PROMISES, as opposed to what it happens to do today.

  `esimprovisioning.store` had no test. It is the actor's SSoT and its ledger, and
  three of its promises are written only as comments in `apply-commit!` -- which means
  a later edit could delete them without anything failing:

    - a transfer REQUEST must not reassign the subject of record. Execution is a
      licensed operator act outside this actor, and a store that quietly moved the
      subject would make the SIM-swap escalation decorative.
    - `:profile/lifecycle-applied` stores the governor's CLEARED outcome and does not
      recompute the next state. Recomputing would be a second opinion, and the whole
      containment rests on there being exactly one.
    - an unknown effect throws rather than being ignored, so a commit this store does
      not understand cannot pass for a commit that happened.

  Every expectation below was measured from the store before it was written down.
  Modelled on cloud-itonami-card-issuing's own store_contract_test."
  (:require [clojure.test :as test :refer [deftest is testing]]
            [esimprovisioning.store :as store]))

(def ^:dynamic *make-store*
  "The constructor under test.

  This suite claimed -- in its own docstring and in every sibling actor's -- that a
  future Datomic or kotoba-server backend could be dropped in behind the same contract
  \"by re-running these tests against the new backend constructor\". It hardcoded
  `store/mem-store`, so that was not true of it: you could not re-run it against
  anything without editing it.

  Now it is a var. `verify-contract!` below runs every assertion against whatever it is
  given, and the deftest at the bottom runs it against MemStore. When a shared backend
  arrives -- which is what ADR-2607300300's D4 needs, since a single kotobase ref is
  what makes cross-domain invariants expressible and the actors are the half that does
  not have one -- adding it is one more deftest, and any behaviour the protocol failed
  to pin shows up as a failure rather than as a surprise in production."
  store/mem-store)

(defn- fresh [] (*make-store*))

;; ---------------------------------------------------------------------------
;; the transfer promise
;; ---------------------------------------------------------------------------

(deftest a-transfer-request-does-not-reassign-the-subject
  (testing "this is the SIM-swap boundary: recording the request is this actor's
            authority, performing it is not"
    (let [db (fresh)
          before (store/subject-of db store/demo-iccid-a)]
      (is (some? before) "the demo subject exists to begin with")
      (store/apply-commit! db {:effect :ownership/transfer-requested
                               :payload {:iccid store/demo-iccid-a
                                         :to-subject "did:key:zAttacker"}})
      (is (= before (store/subject-of db store/demo-iccid-a))
          "the subject of record is unchanged by a mere request")
      (testing "and the request is recorded as NOT executed, explicitly rather than
                by omission"
        (let [t (store/transfer-of db store/demo-iccid-a)]
          (is (false? (:executed t))
              "false, not nil -- absence would leave 'executed?' to a reader's guess")
          (is (= "did:key:zAttacker" (:to-subject t))
              "the requested destination is kept, so an operator can see what was asked"))))))

(deftest a-second-transfer-request-replaces-the-first-rather-than-accumulating
  (testing "measured, and worth pinning either way: the store holds ONE pending
            transfer per ICCID, so an operator reads the current request and not a
            history they have to reconcile"
    (let [db (fresh)]
      (store/apply-commit! db {:effect :ownership/transfer-requested
                               :payload {:iccid store/demo-iccid-a :to-subject "first"}})
      (store/apply-commit! db {:effect :ownership/transfer-requested
                               :payload {:iccid store/demo-iccid-a :to-subject "second"}})
      (is (= "second" (:to-subject (store/transfer-of db store/demo-iccid-a))))
      (is (false? (:executed (store/transfer-of db store/demo-iccid-a)))
          "and a replacement is still not an execution"))))

;; ---------------------------------------------------------------------------
;; one opinion about lifecycle state
;; ---------------------------------------------------------------------------

(deftest lifecycle-applied-stores-the-cleared-outcome-and-does-not-recompute
  (testing "the store is handed the profile vector the governor already cleared
            through kotoba.esim.lifecycle. If it recomputed, there would be two
            authorities on 'what state is this profile in' and they could disagree."
    (let [db (fresh)
          ;; Deliberately NOT what any recomputation would produce: both profiles
          ;; disabled, which kotoba.esim.lifecycle would never derive from an enable.
          cleared [{:esim/iccid store/demo-iccid-a :esim/state :disabled}
                   {:esim/iccid store/demo-iccid-b :esim/state :disabled}]]
      (store/apply-commit! db {:effect :profile/lifecycle-applied
                               :payload {:eid store/demo-eid
                                         :outcome {:esim/profiles cleared}}})
      (is (= cleared (store/profiles-of db store/demo-eid))
          "stored verbatim -- the store took no second opinion")
      (is (nil? (store/enabled-iccid db store/demo-eid))
          "and the derived read follows the stored truth, so nothing re-enables
           a profile behind the governor's back"))))

(deftest enabled-iccid-has-exactly-one-definition
  (testing "delegated to kotoba.esim.lifecycle rather than filtered here, so
            'which profile is enabled' cannot mean two things"
    (let [db (fresh)]
      (is (= store/demo-iccid-a (store/enabled-iccid db store/demo-eid)))
      (testing "an eUICC nobody registered has no enabled profile, and answering
                that is not an error"
        (is (nil? (store/enabled-iccid db "89000000000000000000000000000000")))))))

;; ---------------------------------------------------------------------------
;; installs and registrations
;; ---------------------------------------------------------------------------

(deftest an-install-appends-and-records-the-subject
  (let [db (fresh)
        before (count (store/profiles-of db store/demo-eid))
        iccid "8981012345678900001"]
    (store/apply-commit! db {:effect :profile/installed
                             :payload {:eid store/demo-eid
                                       :iccid iccid
                                       :subject "did:key:zNew"
                                       :profile {:esim/iccid iccid :esim/state :disabled}}})
    (is (= (inc before) (count (store/profiles-of db store/demo-eid)))
        "appended, not replaced -- an install must not drop the incumbent profiles")
    (is (= "did:key:zNew" (store/subject-of db iccid)))
    (testing "and it lands disabled, so installing is not enabling"
      (is (= store/demo-iccid-a (store/enabled-iccid db store/demo-eid))
          "the previously enabled profile is still the enabled one"))))

(deftest registering-a-euicc-defaults-the-variant-to-consumer
  (testing "measured: an absent :variant becomes :consumer rather than nil, because a
            nil variant would later have to be guessed at"
    (let [db (fresh)]
      (store/apply-commit! db {:effect :euicc/registered
                               :payload {:eid "89049032000000000000000000000099"
                                         :manufacturer "Acme"}})
      (let [e (store/euicc db "89049032000000000000000000000099")]
        (is (= :consumer (:esim/variant e)))
        (is (= "Acme" (:esim/manufacturer e))))
      (testing "and an explicit variant is kept"
        (store/apply-commit! db {:effect :euicc/registered
                                 :payload {:eid "89049032000000000000000000000098"
                                           :variant :m2m}})
        (is (= :m2m (:esim/variant (store/euicc db "89049032000000000000000000000098"))))))))

;; ---------------------------------------------------------------------------
;; the ledger, and refusing what it does not understand
;; ---------------------------------------------------------------------------

(deftest the-ledger-appends-in-order-and-starts-empty
  (let [db (fresh)]
    (is (= [] (store/ledger db))
        "a fresh store has recorded nothing -- the demo data is state, not history")
    (store/append-ledger! db {:t :first})
    (store/append-ledger! db {:t :second})
    (is (= [{:t :first} {:t :second}] (store/ledger db))
        "order is preserved, and nothing was overwritten")))

(deftest append-ledger-returns-the-fact-it-recorded
  (testing "so a caller can record and pass on in one expression without re-reading
            the ledger to find out what it wrote"
    (let [db (fresh)
          fact {:t :committed :op :profile/download}]
      (is (= fact (store/append-ledger! db fact))))))

(deftest a-read-is-a-snapshot-and-does-not-change-underneath-its-caller
  (testing "reads deref the atom and hand back immutable values, so a caller holding a
            profile vector or a ledger is not watching it mutate mid-decision -- which
            is what would make a governor's view of 'the profiles' unstable while it
            was reasoning about them"
    (let [db (fresh)
          profiles-then (store/profiles-of db store/demo-eid)
          ledger-then (store/ledger db)]
      (store/append-ledger! db {:t :after})
      (store/apply-commit! db {:effect :profile/lifecycle-applied
                               :payload {:eid store/demo-eid
                                         :outcome {:esim/profiles []}}})
      (is (= [] ledger-then) "the ledger read taken before the append is still empty")
      (is (= 2 (count profiles-then))
          "and the profile vector read before the commit still has both profiles")
      (testing "while a fresh read sees the change"
        (is (= 1 (count (store/ledger db))))
        (is (= [] (store/profiles-of db store/demo-eid)))))))

(deftest an-unknown-commit-effect-throws-rather-than-being-ignored
  (testing "a commit this store cannot apply must not pass for one that happened --
            silence here would leave the ledger claiming an effect the state never took"
    (let [db (fresh)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (store/apply-commit! db {:effect :profile/teleported :payload {}})))
      (testing "and the exception names the effect, so the failure is diagnosable"
        (is (= {:effect :profile/teleported}
               (try (store/apply-commit! db {:effect :profile/teleported :payload {}})
                    (catch clojure.lang.ExceptionInfo e (ex-data e)))))))))

(deftest a-known-commit-returns-the-record-it-applied
  (let [db (fresh)
        record {:effect :smds/event-registered
                :payload {:event-id "ev-1" :eid store/demo-eid}}]
    (is (= record (store/apply-commit! db record)))))

;; ---------------------------------------------------------------------------
;; the demo data is a fixture, and its shape is depended on
;; ---------------------------------------------------------------------------

(deftest the-demo-fixture-is-the-shape-the-tests-and-sim-assume
  (testing "named here so a change to demo-data fails in one obvious place rather
            than scattering across every suite that leans on it"
    (let [db (fresh)]
      (is (= [store/demo-iccid-a store/demo-iccid-b]
             (mapv :esim/iccid (store/profiles-of db store/demo-eid))))
      (is (= [:enabled :disabled]
             (mapv :esim/state (store/profiles-of db store/demo-eid)))
          "exactly one enabled profile, which is the eUICC invariant everything else
           is checked against")
      (is (= 1 (count (store/all-euiccs db)))))))

;; ---------------------------------------------------------------------------
;; running this contract against another implementation
;; ---------------------------------------------------------------------------

(defn verify-contract!
  "Run every assertion in this namespace against `make-store`.

  This is the thing the docstring promised and did not provide. A second backend --
  Datomic, kotoba-server, whatever eventually gives the actors the shared ref that
  ADR-2607300300's D4 asks for -- is added as:

      (deftest datomic-store-satisfies-the-same-contract
        (verify-contract! #(datomic-store/store connection)))

  and every behaviour this file pins is checked against it, without a line of this file
  changing. Anything the Store protocol failed to pin surfaces as a failure here rather
  than as a difference discovered in production.

  Skips itself and the MemStore entry point, or it would recurse."
  [make-store]
  (binding [*make-store* make-store]
    (doseq [[sym v] (ns-publics 'esimprovisioning.store-contract-test)
            :when (and (:test (meta v))
                       (not (contains? #{'mem-store-satisfies-the-contract
                                         'the-contract-runner-actually-runs-something}
                                       sym)))]
      (test/test-var v))))

(deftest mem-store-satisfies-the-contract
  (testing "the same entry point a second backend will use, exercised today against the
            only implementation there is -- so the mechanism is not first tried on the
            day it matters"
    (verify-contract! store/mem-store)))

(deftest the-contract-runner-actually-runs-something
  (testing "a runner that silently selected no tests would pass forever and check
            nothing, which is the failure mode of every 'run them all' helper"
    (let [n (count (for [[sym v] (ns-publics 'esimprovisioning.store-contract-test)
                         :when (and (:test (meta v))
                                    (not (contains? #{'mem-store-satisfies-the-contract
                                                      'the-contract-runner-actually-runs-something}
                                                    sym)))]
                     sym))]
      (is (<= 10 n) (str "expected the contract to be more than a handful of tests, got " n)))))
