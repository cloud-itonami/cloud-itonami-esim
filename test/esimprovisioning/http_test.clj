(ns esimprovisioning.http-test
  "The wire behaviour, over a real server on a real socket.

  The property this file exists for: a Passkey consent arriving from a consent
  surface is NOT this actor's operator approval, and nothing in the request body
  can make it one."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [esimprovisioning.http :as http]
            [esimprovisioning.store :as store])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Duration]))

(def eid store/demo-eid)
(def iccid-a store/demo-iccid-a)
(def iccid-b store/demo-iccid-b)
(def iccid-bad "8981012345678901231")

(defonce ^HttpClient client
  (-> (HttpClient/newBuilder) (.connectTimeout (Duration/ofSeconds 5)) (.build)))

(defn- post [port path body]
  (let [req (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" port path)))
                (.timeout (Duration/ofSeconds 10))
                (.header "Content-Type" "application/json")
                (.POST (HttpRequest$BodyPublishers/ofString (json/write-str body)))
                (.build))
        res (.send client req (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode res)
     :body (try (json/read-str (.body res) :key-fn keyword)
                (catch Exception _ nil))}))

(defn- get! [port path]
  (let [req (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" port path)))
                (.timeout (Duration/ofSeconds 10))
                (.GET) (.build))
        res (.send client req (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode res)
     :body (try (json/read-str (.body res) :key-fn keyword)
                (catch Exception _ nil))}))

(defn- with-server
  "Run f with a server on an ephemeral-ish port, always stopping it."
  [f]
  (let [st (store/mem-store)
        server (http/start! {:port 0 :store st})
        port (.getPort (.getAddress server))]
    (try (f port st)
         (finally (.stop server 0)))))

;; A proposal in the shape cloud-itonami-app's transport sends: JSON, so the op
;; and the value's keyword fields arrive as strings.
(defn- proposal [op value]
  {:proposal {:id "p-1" :authority "esim" :op op :value value
              :digest "digest-1" :status "approved"
              :passkey-credential-id "cred-1"}})

;; ---------------------------------------------------------------------------

(deftest healthz-answers
  (with-server
    (fn [port _]
      (let [{:keys [status body]} (get! port "/healthz")]
        (is (= 200 status))
        (is (= "ok" (:status body)))
        (is (= "cloud-itonami-esim" (:actor body)))
        (is (= 1 (:euiccs body)) "the demo store has one registered eUICC")))))

(deftest a-consented-proposal-ends-pending-not-committed
  (testing "this is the two gates on the wire: the subject consented, and this
            actor still needs its OWN operator"
    (with-server
      (fn [port _]
        (let [{:keys [status body]}
              (post port "/commit"
                    (proposal "profile/lifecycle"
                              {:eid eid :iccid iccid-a :operation "disable"}))]
          (is (= 200 status))
          (is (= "pending" (:status body)))
          (is (string? (:reference body)))
          (is (nil? (:record body)) "nothing was committed")
          (is (nil? (:refusal body)) "and nothing was refused"))))))

(deftest no-field-in-the-body-can-buy-an-operator-approval
  (testing "a caller cannot smuggle the approval the interrupt is waiting for"
    (with-server
      (fn [port st]
        (let [base (proposal "profile/lifecycle"
                             {:eid eid :iccid iccid-a :operation "disable"})]
          (testing "smuggled alongside the proposal"
            (doseq [extra [{:approval {:status "approved" :by "attacker"}}
                           {:disposition "commit"}
                           {:verdict {:ok? true}}
                           {:resume? true}]]
              (let [{:keys [body]} (post port "/commit" (merge base extra))]
                (is (= "pending" (:status body))
                    (str "smuggled " (pr-str (keys extra)) " must not commit")))))
          (testing "smuggled INSIDE the proposal, which is the shape a caller
                    controls -- a merge at the top level would have replaced the
                    proposal instead of adding to it, so this is the case that
                    actually tests the interrupt"
            (doseq [extra [{:approval {:status "approved" :by "attacker"}}
                           {:disposition "commit"}
                           {:status "approved-by-operator"}]]
              (let [body (update base :proposal merge extra)
                    {:keys [body]} (post port "/commit" body)]
                (is (= "pending" (:status body))
                    (str "smuggled into the proposal: " (pr-str (keys extra))))))))
        (testing "and the profile really did not move"
          (is (= :enabled (:esim/state (store/profile-of st eid iccid-a)))))))))

(deftest a-governor-refusal-comes-back-as-held
  (with-server
    (fn [port st]
      (testing "a tampered ICCID check digit"
        (let [{:keys [body]} (post port "/commit"
                                   (proposal "profile/download"
                                             {:eid eid :iccid iccid-bad}))]
          (is (= "held" (:status body)))
          (is (= "iccid-invalid" (name (keyword (get-in body [:refusal :rule])))))))
      (testing "enabling a second profile while one is enabled"
        (let [{:keys [body]} (post port "/commit"
                                   (proposal "profile/lifecycle"
                                             {:eid eid :iccid iccid-b
                                              :operation "enable"}))]
          (is (= "held" (:status body)))
          (is (= "enable-would-displace"
                 (name (keyword (get-in body [:refusal :rule])))))))
      (testing "an unregistered eUICC"
        (let [{:keys [body]} (post port "/commit"
                                   (proposal "profile/lifecycle"
                                             {:eid "89049032000000000000000000000009"
                                              :iccid iccid-a :operation "disable"}))]
          (is (= "held" (:status body)))))
      (testing "and nothing changed in the store through any of it"
        (is (= :enabled (:esim/state (store/profile-of st eid iccid-a))))
        (is (= :disabled (:esim/state (store/profile-of st eid iccid-b))))))))

(deftest an-unknown-op-is-refused-not-coerced
  (with-server
    (fn [port _]
      (doseq [op ["profile/nuke" "" "not-a-keyword-at-all"]]
        (let [{:keys [body]} (post port "/commit" (proposal op {:eid eid}))]
          (is (= "held" (:status body)) (str "op " (pr-str op))))))))

(deftest a-malformed-request-is-refused-with-a-reason
  (with-server
    (fn [port _]
      (let [{:keys [status body]} (post port "/commit" {:not-a-proposal true})]
        (is (= 400 status))
        (is (= "held" (:status body)))
        (is (= "malformed-request" (name (keyword (get-in body [:refusal :rule]))))))
      (testing "an unknown path is a refusal, not an empty 404"
        (let [{:keys [status body]} (post port "/nope" {})]
          (is (= 404 status))
          (is (= "held" (:status body))))))))

(deftest every-answer-is-one-of-the-three-states
  (with-server
    (fn [port _]
      (doseq [[op value] [["profile/lifecycle" {:eid eid :iccid iccid-a :operation "disable"}]
                          ["profile/download" {:eid eid :iccid iccid-bad}]
                          ["ownership/transfer" {:eid eid :iccid iccid-a
                                                 :from-subject "did:key:zDemoSubjectA"
                                                 :to-subject "did:key:zB"}]
                          ["profile/nuke" {:eid eid}]]]
        (let [{:keys [body]} (post port "/commit" (proposal op value))]
          (is (contains? #{"committed" "held" "pending"} (:status body))
              (str op " -> " (:status body))))))))

(deftest an-ownership-transfer-is-never-auto-committed
  (testing "the primary SIM-swap path must always reach a human"
    (with-server
      (fn [port st]
        (let [{:keys [body]} (post port "/commit"
                                   (proposal "ownership/transfer"
                                             {:eid eid :iccid iccid-a
                                              :from-subject "did:key:zDemoSubjectA"
                                              :to-subject "did:key:zAttacker"}))]
          (is (= "pending" (:status body)))
          (testing "and the subject of record has not moved"
            (is (= "did:key:zDemoSubjectA" (store/subject-of st iccid-a)))
            (is (nil? (store/transfer-of st iccid-a)))))))))

(deftest the-ledger-records-what-the-surface-answered
  (with-server
    (fn [port st]
      (post port "/commit" (proposal "profile/download" {:eid eid :iccid iccid-bad}))
      (let [ledger (store/ledger st)]
        (is (some #(= :advised (:t %)) ledger)
            "the proposal that was refused is still on the ledger")
        (is (some #(= :held (:t %)) ledger)
            "and so is the refusal")))))

(deftest proposal-mapping-keeps-namespaced-keywords
  (is (= :profile/lifecycle (:op (http/proposal->request
                                  {:op "profile/lifecycle" :value {}}))))
  (is (= :disable (get-in (http/proposal->request
                           {:op "profile/lifecycle"
                            :value {:eid eid :operation "disable"}})
                          [:value :operation])))
  (testing "the subject is the EID, which is what this actor's ops are keyed on"
    (is (= eid (:subject (http/proposal->request
                          {:op "profile/lifecycle" :value {:eid eid}})))))
  (testing "an unusable op becomes nil rather than something plausible"
    (is (nil? (:op (http/proposal->request {:op "" :value {}}))))
    (is (nil? (:op (http/proposal->request {:op nil :value {}}))))))
