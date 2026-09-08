(ns esimprovisioning.advisor-test
  "The containment properties of the intelligence node.

  esimprovisioning.advisor had no test. Its docstring makes three claims that the
  whole containment rests on, and none of them was checked:

    - an advisor returns a PROPOSAL and nothing else. It cannot write, cannot
      actuate, and cannot mark its own proposal approved.
    - the ops that change whether a real line works, or who controls it, are marked
      :actuation -- which is what makes the governor and every phase require a human.
    - `trace` masks identifiers, because the ledger is read by operators and must not
      be the place a device-bound identifier leaks.

  A proposal is untrusted input by design, so what matters here is not that the mock
  returns nice values -- it is that the SHAPE cannot carry authority and that the
  actuation marking covers exactly the right ops. cloud-itonami-card-issuing has the
  equivalent file for its own advisor; this is the one that was missing.

  Every expectation was measured before being written down."
  (:require [clojure.set :as set]
            [kotoba.lang.text :as str]
            [clojure.test :refer [deftest is testing]]
            [esimprovisioning.advisor :as advisor]
            [esimprovisioning.registry :as registry]
            [esimprovisioning.store :as store]))

(defn- advise [op & [value]]
  (advisor/-advise (advisor/mock-advisor) (store/mem-store)
                   {:op op :subject store/demo-eid :value value}))

(def ^:private proposal-keys
  #{:effect :value :summary :cites :stake :confidence})

;; ---------------------------------------------------------------------------
;; a proposal cannot carry authority
;; ---------------------------------------------------------------------------

(deftest a-proposal-has-no-field-that-could-approve-it
  (testing "the advisor cannot mark its own proposal approved -- there is nowhere in
            the shape to put that, which is stronger than a rule saying it must not"
    (doseq [op [:coverage/report :profile/download :ownership/transfer]]
      (let [p (advise op)]
        (is (= proposal-keys (set (keys p))) (str op ": unexpected keys"))
        (doseq [forbidden [:approved :approved? :approval :disposition :verdict
                           :committed :ok?]]
          (is (not (contains? p forbidden))
              (str op " must not carry " forbidden)))))))

(deftest a-proposal-is-inert-data
  (testing "no functions, no atoms, no side-channel -- a proposal a governor rejects
            must not be able to have done anything on the way"
    (let [p (advise :profile/download {:iccid store/demo-iccid-a})]
      (is (map? p))
      (doseq [[k v] p]
        (is (not (fn? v)) (str k " is callable"))
        (is (not (instance? clojure.lang.IDeref v)) (str k " is dereferenceable"))))))

;; ---------------------------------------------------------------------------
;; the actuation marking
;; ---------------------------------------------------------------------------

(deftest exactly-the-three-line-changing-ops-are-marked-actuation
  (testing "this marking is what makes the governor and every phase require a human,
            so both directions matter: none missing, and none added"
    (let [ops [:coverage/report :euicc/register :profile/download :profile/lifecycle
               :smds/register :ownership/transfer]
          marked (set (for [op ops :when (= :actuation (:stake (advise op)))] op))]
      (is (= #{:profile/download :profile/lifecycle :ownership/transfer} marked))
      (testing "and the unmarked ones are unmarked because they change no line"
        (is (empty? (set/intersection marked #{:coverage/report :euicc/register
                                               :smds/register})))))))

(deftest ownership-transfer-is-marked-actuation-and-not-merely-a-record-write
  (testing "the store records a transfer REQUEST without moving the subject, but the
            proposal still carries :actuation -- because who controls a line is what
            a SIM-swap changes, and a human decides that"
    (is (= :actuation (:stake (advise :ownership/transfer))))))

(deftest a-read-only-op-carries-no-stake
  (is (nil? (:stake (advise :coverage/report)))))

;; ---------------------------------------------------------------------------
;; an op the advisor does not know
;; ---------------------------------------------------------------------------

(deftest an-unknown-op-yields-a-nil-effect-rather-than-a-plausible-one
  (testing "the advisor does not invent an effect for an op it has no mapping for.
            A nil :effect reaches the governor's allowlist as nil and is refused
            there -- guessing the nearest effect is how an unknown request becomes a
            write nobody asked for."
    (doseq [op [:profile/teleport :euicc/destroy nil]]
      (let [p (advise op)]
        (is (nil? (:effect p)) (pr-str op))
        (is (nil? (:stake p)) (str (pr-str op) ": an unknown op is not actuation either"))))
    (testing "and it says so in the summary rather than naming a real op"
      (is (str/includes? (:summary (advise nil)) "unknown")))))

;; ---------------------------------------------------------------------------
;; the ledger must not be where an identifier leaks
;; ---------------------------------------------------------------------------

(deftest the-trace-masks-the-subject
  (let [request {:op :profile/download :subject store/demo-eid}
        t (advisor/trace request (advise :profile/download))]
    (is (= (registry/masked store/demo-eid) (:subject t)))
    (is (not= store/demo-eid (:subject t)) "the raw EID is not in the ledger fact")
    (testing "and the mask really is a mask, not a passthrough"
      (is (< (count (:subject t)) (count store/demo-eid)))
      (is (str/starts-with? (:subject t) "...")))))

(deftest the-trace-carries-no-payload
  (testing "the value is where the identifiers live; a trace that included it would
            put them in the ledger by the back door"
    (let [t (advisor/trace {:op :profile/download :subject store/demo-eid}
                           (advise :profile/download {:iccid store/demo-iccid-a}))]
      (is (not (contains? t :value)))
      (is (not (str/includes? (pr-str t) store/demo-iccid-a))
          "no ICCID anywhere in the recorded fact")
      (is (not (str/includes? (pr-str t) store/demo-eid))
          "and no unmasked EID either"))))

(deftest the-summary-is-masked-too
  (testing "the summary lands in the ledger alongside the trace, so masking the
            :subject field alone would not be enough"
    (let [p (advise :profile/download)]
      (is (not (str/includes? (:summary p) store/demo-eid)))
      (is (str/includes? (:summary p) (registry/masked store/demo-eid))))))

(deftest the-trace-is-what-was-asked-and-what-came-back
  (testing "both halves, so a ledger reader can see a proposal that was refused as
            well as one that was committed"
    (let [p (advise :ownership/transfer)
          t (advisor/trace {:op :ownership/transfer :subject store/demo-eid} p)]
      (is (= :advised (:t t)))
      (is (= :ownership/transfer (:op t)) "what was asked")
      (is (= (:effect p) (:effect t)) "what came back")
      (is (= :actuation (:stake t)) "and that a human is required")
      (is (= (:confidence p) (:confidence t))))))

;; ---------------------------------------------------------------------------
;; the mock is deliberately capable of proposing something inadmissible
;; ---------------------------------------------------------------------------

(deftest the-mock-is-deterministic
  (testing "the same request twice gives the same proposal, so a governor test that
            fails means the governor changed"
    (is (= (advise :profile/download {:iccid store/demo-iccid-a})
           (advise :profile/download {:iccid store/demo-iccid-a})))))

(deftest the-mock-will-propose-things-the-governor-must-refuse
  (testing "an advisor that only ever proposed admissible things could not exercise a
            censor -- its docstring says so, and this is what that means in practice:
            it happily marks a real-line change with high confidence and leaves the
            decision to the governor"
    (let [p (advise :ownership/transfer)]
      (is (= :actuation (:stake p)))
      (is (<= 0.9 (:confidence p))
          "high confidence is not authority -- the governor treats it as untrusted"))))

(deftest the-caller-supplied-value-is-carried-not-sanitised
  (testing "the advisor merges the request's value into the payload as-is. That is
            correct here BECAUSE the governor recomputes every ground-truth fact from
            recorded state -- a proposal is untrusted input, and pretending to clean
            it in the advisor would move the check to the wrong layer."
    (let [p (advise :profile/download {:iccid "not-an-iccid" :invented true})]
      (is (= "not-an-iccid" (get-in p [:value :iccid])))
      (is (true? (get-in p [:value :invented]))
          "even a field nobody asked for survives, and the governor is what refuses it"))))
