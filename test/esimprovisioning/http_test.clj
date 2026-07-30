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

(def consent "test-esim-consent-token")

(defn- post
  "POST with the consent token attached -- the consent surface requires it. The
  refusal paths are exercised through post-with-header, which attaches nothing."
  [port path body]
  (let [req (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" port path)))
                (.timeout (Duration/ofSeconds 10))
                (.header "Content-Type" "application/json")
                (.header "X-ESIM-CONSENT-TOKEN" consent)
                (.POST (HttpRequest$BodyPublishers/ofString (json/write-str body)))
                (.build))
        res (.send client req (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode res)
     :body (try (json/read-str (.body res) :key-fn keyword)
                (catch Exception _ nil))}))

(defn- get! [port path]
  (let [req (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" port path)))
                (.timeout (Duration/ofSeconds 10))
                (.header "X-ESIM-CONSENT-TOKEN" consent)
                (.GET) (.build))
        res (.send client req (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode res)
     :body (try (json/read-str (.body res) :key-fn keyword)
                (catch Exception _ nil))}))

(defn- with-server
  "Run f with both surfaces on ephemeral ports, always stopping them.
  f receives [consent-port store operator-port]."
  [f]
  (with-redefs [http/consent-token (constantly consent)]
   (let [st (store/mem-store)
        running (http/start! {:port 0 :operator-port 0 :store st})
        ;; HttpServer/getAddress already returns the InetSocketAddress; calling
        ;; getAddress twice lands on an InetAddress, which has no getPort.
        cport (.getPort (.getAddress ^com.sun.net.httpserver.HttpServer
                                    (:consent running)))
        oport (.getPort (.getAddress ^com.sun.net.httpserver.HttpServer
                                     (:operator running)))]
    (try (f cport st oport)
         (finally (http/stop! running))))))

(defn- post-with-header [port path body header value]
  (let [b (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" port path)))
              (.timeout (Duration/ofSeconds 10))
              (.header "Content-Type" "application/json"))
        b (cond-> b value (.header header value))
        res (.send client (.build (.POST b (HttpRequest$BodyPublishers/ofString
                                            (json/write-str body))))
                   (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode res)
     :body (try (json/read-str (.body res) :key-fn keyword)
                (catch Exception _ nil))}))

;; A proposal in the shape cloud-itonami-app's transport sends: JSON, so the op
;; and the value's keyword fields arrive as strings.
(defn- proposal [op value]
  {:proposal {:id "p-1" :authority "esim" :op op :value value
              :digest "digest-1" :status "approved"
              :passkey-credential-id "cred-1"}})

;; ---------------------------------------------------------------------------

(deftest healthz-answers
  (with-server
    (fn [port _ _oport]
      (let [{:keys [status body]} (get! port "/healthz")]
        (is (= 200 status))
        (is (= "ok" (:status body)))
        (is (= "cloud-itonami-esim" (:actor body)))
        (is (= 1 (:euiccs body)) "the demo store has one registered eUICC")))))

(deftest a-consented-proposal-ends-pending-not-committed
  (testing "this is the two gates on the wire: the subject consented, and this
            actor still needs its OWN operator"
    (with-server
      (fn [port _ _oport]
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
      (fn [port st _oport]
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
    (fn [port st _oport]
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
    (fn [port _ _oport]
      (doseq [op ["profile/nuke" "" "not-a-keyword-at-all"]]
        (let [{:keys [body]} (post port "/commit" (proposal op {:eid eid}))]
          (is (= "held" (:status body)) (str "op " (pr-str op))))))))

(deftest a-malformed-request-is-refused-with-a-reason
  (with-server
    (fn [port _ _oport]
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
    (fn [port _ _oport]
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
      (fn [port st _oport]
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
    (fn [port st _oport]
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

;; ---------------------------------------------------------------------------
;; resolving a pending proposal
;; ---------------------------------------------------------------------------

(def ^:private token "test-operator-token")

(defn- pending-reference
  "Push a proposal to pending and return its reference."
  [port]
  (let [{:keys [body]} (post port "/commit"
                             (proposal "profile/lifecycle"
                                       {:eid eid :iccid iccid-a :operation "disable"}))]
    (assert (= "pending" (:status body)) (pr-str body))
    (:reference body)))

(deftest a-pending-reference-can-be-read-back
  (with-server
    (fn [port _ _oport]
      (let [ref (pending-reference port)
            {:keys [status body]} (get! port (str "/proposals/" ref))]
        (is (= 200 status))
        (is (= "pending" (:status body)))
        (is (= ref (:reference body)))
        (is (false? (:resolved? body)) "the operator has not decided yet")))))

(deftest an-unknown-reference-answers-unknown-not-pending
  (with-server
    (fn [port _ _oport]
      (let [{:keys [body]} (get! port "/proposals/never-existed")]
        (is (= "unknown" (:status body))
            "reporting an unknown reference as pending would be a guess dressed
             as a fact -- and after a restart every old reference is unknown")))))

(deftest the-consent-surface-cannot-decide
  (testing "this is D3 as a port boundary: if the consent surface could decide, it
            would hold both gates"
    (with-server
      (fn [port _ _oport]
        (let [ref (pending-reference port)]
          (doseq [path [(str "/proposals/" ref "/decide")
                        (str "/operator/proposals/" ref "/decide")
                        "/decide"]]
            (let [{:keys [status body]} (post port path {:status "approved" :by "app"})]
              (is (= 404 status) (str "consent surface must not route " path))
              (is (= "held" (:status body)))))
          (testing "and the proposal is still pending"
            (is (= "pending" (:status (:body (get! port (str "/proposals/" ref))))))))))))

(deftest the-operator-surface-fails-closed-without-a-token
  (testing "an unauthenticated decide endpoint is a way to approve a real line cut"
    (with-server
      (fn [port _ oport]
        (let [ref (pending-reference port)
              {:keys [status body]} (post-with-header
                                     oport (str "/proposals/" ref "/decide")
                                     {:status "approved" :by "operator@example"}
                                     "X-ESIM-OPERATOR-TOKEN" nil)]
          ;; ESIM_OPERATOR_TOKEN is not set in the test JVM.
          (is (= 503 status))
          (is (= "operator-surface-unconfigured"
                 (name (keyword (get-in body [:refusal :rule])))))
          (testing "and the proposal did not move"
            (is (= "pending" (:status (:body (get! port (str "/proposals/" ref))))))))))))

(deftest a-wrong-token-is-refused
  (testing "with a token configured, a mismatched one is 401 -- checked by
            temporarily binding the env lookup"
    (with-redefs [http/operator-token (constantly token)]
      (with-server
        (fn [port _ oport]
          (let [ref (pending-reference port)]
            (let [{:keys [status body]} (post-with-header
                                         oport (str "/proposals/" ref "/decide")
                                         {:status "approved" :by "op"}
                                         "X-ESIM-OPERATOR-TOKEN" "wrong")]
              (is (= 401 status))
              (is (= "operator-token-mismatch"
                     (name (keyword (get-in body [:refusal :rule]))))))
            (is (= "pending" (:status (:body (get! port (str "/proposals/" ref))))))))))))

(deftest an-operator-approval-resolves-the-proposal
  (with-redefs [http/operator-token (constantly token)]
    (with-server
      (fn [port st oport]
        (let [ref (pending-reference port)]
          (testing "before the decision, the profile has not moved"
            (is (= :enabled (:esim/state (store/profile-of st eid iccid-a)))))
          (let [{:keys [status body]} (post-with-header
                                       oport (str "/proposals/" ref "/decide")
                                       {:status "approved" :by "operator@example"}
                                       "X-ESIM-OPERATOR-TOKEN" token)]
            (is (= 200 status))
            (is (= "committed" (:status body)))
            (is (= "operator@example" (:decided-by body))))
          (testing "the profile really moved, and the consent surface can read it back"
            (is (= :disabled (:esim/state (store/profile-of st eid iccid-a))))
            (let [{:keys [body]} (get! port (str "/proposals/" ref))]
              (is (= "committed" (:status body)))
              (is (true? (:resolved? body)))))
          (testing "and the ledger names the approver"
            (let [c (first (filter #(= :committed (:t %)) (store/ledger st)))]
              (is (= "operator@example" (:approved-by c))))))))))

(deftest an-operator-rejection-holds-it
  (with-redefs [http/operator-token (constantly token)]
    (with-server
      (fn [port st oport]
        (let [ref (pending-reference port)
              {:keys [body]} (post-with-header
                              oport (str "/proposals/" ref "/decide")
                              {:status "rejected" :by "operator@example"}
                              "X-ESIM-OPERATOR-TOKEN" token)]
          (is (= "held" (:status body)))
          (is (= :enabled (:esim/state (store/profile-of st eid iccid-a)))
              "a rejection must not move anything")
          (let [r (first (filter #(= :approval-rejected (:t %)) (store/ledger st)))]
            (is (some? r))
            (is (= "operator@example" (:by r)))))))))

(deftest an-approval-nobody-is-named-for-is-refused
  (with-redefs [http/operator-token (constantly token)]
    (with-server
      (fn [port st oport]
        (let [ref (pending-reference port)]
          (doseq [body [{:status "approved"}
                        {:status "approved" :by ""}
                        {:status "approved" :by nil}]]
            (let [{:keys [body]} (post-with-header
                                  oport (str "/proposals/" ref "/decide")
                                  body "X-ESIM-OPERATOR-TOKEN" token)]
              (is (= "approver-unnamed"
                     (name (keyword (get-in body [:refusal :rule]))))
                  "an approval nobody is named for cannot be audited")))
          (testing "and a malformed status is refused too"
            (let [{:keys [body]} (post-with-header
                                  oport (str "/proposals/" ref "/decide")
                                  {:status "maybe" :by "op"}
                                  "X-ESIM-OPERATOR-TOKEN" token)]
              (is (= "malformed-decision"
                     (name (keyword (get-in body [:refusal :rule])))))))
          (is (= :enabled (:esim/state (store/profile-of st eid iccid-a)))))))))

(deftest deciding-an-unknown-reference-answers-unknown
  (with-redefs [http/operator-token (constantly token)]
    (with-server
      (fn [_port _ oport]
        (let [{:keys [body]} (post-with-header
                              oport "/proposals/never-existed/decide"
                              {:status "approved" :by "op"}
                              "X-ESIM-OPERATOR-TOKEN" token)]
          (is (= "unknown" (:status body))))))))

;; ---------------------------------------------------------------------------
;; the consent surface's own token
;; ---------------------------------------------------------------------------

(deftest a-proposal-without-the-consent-token-is-refused
  (testing "loopback is not an authorisation: without this, any local process could
            claim a subject consented, and the only thing left between that and a
            provisioned -- or transferred -- line would be this actor's operator"
    (with-server
      (fn [port _ _]
        (let [{:keys [status body]} (post-with-header
                                     port "/commit"
                                     {:proposal {:id "p-1" :op "profile/download"}}
                                     "X-Unrelated" "x")]
          (is (= 401 status))
          (is (= "consent-token-mismatch"
                 (name (keyword (get-in body [:refusal :rule]))))))))))

(deftest a-wrong-consent-token-is-refused
  (with-server
    (fn [port _ _]
      (let [{:keys [status body]} (post-with-header
                                   port "/commit"
                                   {:proposal {:id "p-1" :op "profile/download"}}
                                   "X-ESIM-CONSENT-TOKEN" "wrong")]
        (is (= 401 status))
        (is (= "consent-token-mismatch"
               (name (keyword (get-in body [:refusal :rule])))))))))

(deftest an-unset-consent-token-refuses-rather-than-waving-through
  (with-redefs [http/consent-token (constantly nil)]
    (let [running (http/start! {:port 0 :operator-port 0 :store (store/mem-store)})
          port (.getPort (.getAddress ^com.sun.net.httpserver.HttpServer
                                      (:consent running)))]
      (try
        (let [{:keys [status body]} (post-with-header
                                     port "/commit"
                                     {:proposal {:id "p-1" :op "profile/download"}}
                                     "X-ESIM-CONSENT-TOKEN" "anything")]
          (is (= 503 status))
          (is (= "consent-surface-unconfigured"
                 (name (keyword (get-in body [:refusal :rule]))))))
        (finally (http/stop! running))))))

(deftest healthz-stays-open-and-the-reads-do-not
  (with-server
    (fn [port _ _]
      (testing "healthz carries no subject data and must answer before a token exists"
        (let [req (-> (HttpRequest/newBuilder
                       (URI/create (str "http://127.0.0.1:" port "/healthz")))
                      (.timeout (Duration/ofSeconds 10)) (.GET) (.build))
              res (.send client req (HttpResponse$BodyHandlers/ofString))]
          (is (= 200 (.statusCode res)))))
      (testing "a proposal read names a subject's reference, so it does need the token"
        (let [req (-> (HttpRequest/newBuilder
                       (URI/create (str "http://127.0.0.1:" port "/proposals/p-1")))
                      (.timeout (Duration/ofSeconds 10)) (.GET) (.build))
              res (.send client req (HttpResponse$BodyHandlers/ofString))]
          (is (= 401 (.statusCode res))))))))
