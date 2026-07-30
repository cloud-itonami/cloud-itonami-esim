(ns esimprovisioning.http
  "The governed HTTP surface a consent surface hands proposals to.

  `POST /commit` takes a proposal that a consent surface (cloud-itonami-app) has
  already obtained human consent for, runs it through THIS actor's own graph --
  advisor, governor, phase gate -- and answers with what happened.

  THE TWO GATES DO NOT SUBSTITUTE, and this namespace is where that stops being a
  design statement and becomes wire behaviour. A proposal arriving here carries a
  Passkey consent from the subject. That consent is NOT this actor's operator
  approval and is never treated as one: the graph's `interrupt-before
  #{:request-approval}` is not resumed here, and no field in the request body can
  cause it to be. The subject agreeing that they want a thing is a different
  question from the licensed operator agreeing that it may happen.

  So there are three outcomes, not two:

    {\"status\": \"committed\", \"record\": {...}}   governor clear AND phase-auto
    {\"status\": \"held\",      \"refusal\": {...}}  governor refused (HARD)
    {\"status\": \"pending\",   \"reference\": \"…\"} accepted, awaiting this
                                                  actor's own operator

  Worth stating plainly what that means for today's fleet: every op a consent
  surface can send here -- `:profile/download`, `:profile/lifecycle`,
  `:ownership/transfer` -- is absent from EVERY phase's `:auto` set, permanently
  (see `esimprovisioning.phase`). So a well-formed proposal ends in \"pending\",
  never in \"committed\". That is the design working, not a limitation to route
  around: a real profile download, a real line cut and a real number transfer are
  always a human call. `committed` exists on this surface for ops that a future
  phase legitimately auto-commits, and for an operator-facing caller.

  TWO SURFACES, ON TWO LISTENERS, AND THAT SEPARATION IS THE POINT.

    consent surface (default :1339)   POST /commit
                                      GET  /proposals/<reference>
                                      GET  /healthz
    operator surface (default :1340)  POST /proposals/<reference>/decide

  A pending proposal is waiting for THIS actor's operator. If the decide endpoint
  sat on the consent surface, the consent surface could approve its own
  proposals -- the app would hold both gates, and D3 would be a comment rather
  than a boundary. So they are different listeners: the consent surface cannot
  reach decide because it is not listening on that port, which is a structural
  separation rather than a policy check that someone can forget.

  The operator surface additionally requires a shared secret from
  ESIM_OPERATOR_TOKEN, and REFUSES EVERY DECIDE WHEN THAT IS UNSET. Failing closed
  matters more here than convenience: an unauthenticated decide endpoint is a way
  to approve a real line cut or a real number transfer.

  The store is a per-process MemStore, so a restart forgets. That is R0: the
  shared durable plane (ADR-2607300300 D4) is a separate change, and pretending
  otherwise by persisting to a local file would give the appearance of durability
  without the cross-domain reach that matters. It also means a pending reference
  does not survive a restart -- a caller polling one will get \"unknown\", which is
  honest about what was lost rather than silently reporting it as still pending."
  (:require [clojure.data.json :as json]
            [langgraph.graph :as g]
            [esimprovisioning.operation :as operation]
            [esimprovisioning.store :as store])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.io ByteArrayOutputStream]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]))

(def ^:private max-body-bytes
  "A proposal is a small map. Anything larger is not one, and reading it would be
  the only unbounded allocation on this surface."
  65536)

(def actor-context
  {:actor-id "cloud-itonami-esim" :role :provisioning-operator})

;; ---------------------------------------------------------------------------
;; wire helpers
;; ---------------------------------------------------------------------------

(defn- read-body [^HttpExchange exchange]
  (with-open [in (.getRequestBody exchange)
              out (ByteArrayOutputStream.)]
    (let [buf (byte-array 8192)]
      (loop [total 0]
        (let [n (.read in buf)]
          (cond
            (neg? n) (.toString out StandardCharsets/UTF_8)
            (> (+ total n) max-body-bytes)
            (throw (ex-info "request body too large" {:type :http/body-too-large}))
            :else (do (.write out buf 0 n) (recur (+ total n)))))))))

(defn- send! [^HttpExchange exchange status payload]
  (let [bytes (.getBytes (json/write-str payload) StandardCharsets/UTF_8)]
    (doto (.getResponseHeaders exchange)
      (.set "Content-Type" "application/json; charset=utf-8")
      (.set "Cache-Control" "no-store"))
    (.sendResponseHeaders exchange status (alength bytes))
    (with-open [out (.getResponseBody exchange)]
      (.write out bytes))
    (.close exchange)))

(defn- kw
  "Read a keyword the wire sent as a string, preserving its namespace:
  \"profile/lifecycle\" -> :profile/lifecycle. Returns nil for anything else, so
  an unrecognised op reaches the governor's op allowlist as nil and is refused
  there rather than being coerced into something plausible."
  [v]
  (cond
    (keyword? v) v
    (and (string? v) (seq v)) (keyword v)
    :else nil))

(defn- keywordize-value
  "The proposal's :value arrives as JSON, so its own keyword fields are strings.
  Only the fields this actor's ops actually read are converted -- an unknown field
  is left alone rather than blanket-keywordized, because blanket conversion would
  turn arbitrary caller strings into interned keywords."
  [value]
  (cond-> (or value {})
    (contains? value :operation) (update :operation kw)
    (contains? value :variant) (update :variant kw)))

;; ---------------------------------------------------------------------------
;; the commit path
;; ---------------------------------------------------------------------------

(defn proposal->request
  "Map a consent surface's proposal onto this actor's own request.

  The `:value` is passed through but NOT trusted: `esimprovisioning.governor`
  recomputes every ground-truth fact from this actor's own recorded state, so a
  proposal cannot assert its way past a check by describing a state that is not
  ours. `:subject` is the EID, which is what this actor's ops are keyed on."
  [{:keys [op value]}]
  (let [value (keywordize-value value)]
    {:op (kw op)
     :subject (:eid value)
     :value value}))

(defn- disposition->wire
  "Map a graph state's disposition onto the wire vocabulary. One function so the
  commit path and the status path cannot drift into describing the same
  disposition differently."
  [state thread]
  (let [verdict (:verdict state)]
    (case (:disposition state)
      :commit
      {:status "committed"
       :record (merge {:reference thread} (:payload (:record state)))}

      :escalate
      {:status "pending"
       :reference thread
       :reason (str (or (some-> (last (:audit state)) :reason) :actuation))
       :detail (str "この操作は " (:actor-id actor-context)
                    " 自身の operator 承認を必要とします（consent surface の "
                    "Passkey 同意は operator 承認ではありません）")}

      {:status "held"
       :refusal {:rule (or (some-> (:violations verdict) first :rule) :held)
                 :violations (mapv :rule (:violations verdict))
                 :confidence (:confidence verdict)}})))

(defn commit-outcome
  "Run one proposal through the actor and return the wire answer.

  Never resumes the approval interrupt. A `:escalate` disposition means this
  actor accepted the proposal and is holding it for its OWN operator, which is a
  third outcome distinct from both success and refusal.

  EVERY CALL GETS A FRESH THREAD ID, and that is a correctness requirement rather
  than a detail. langgraph's `run*` continues an existing thread's state when a
  checkpoint exists for that id -- `base (if latest (merge base (:state latest)))`
  -- so keying the thread on the proposal id would make a second POST for the same
  proposal inherit the first run's channels, including `:approval`. A caller
  controls the request body, so a caller who sent an `:approval` on one call could
  have it carried into the next by the checkpointer. Found by a test that sent
  smuggled fields on a loop and watched the third one commit.

  The consequence is deliberate: two POSTs for one proposal are two attempts, each
  independently held for the operator, rather than one attempt that accumulates
  state. The returned `:reference` is that thread id, which is what an operator
  resumes.

  Takes only the compiled `app`: the store is already bound inside it by
  `operation/build`, and taking a second reference here would invite a caller to
  pass a DIFFERENT store than the graph writes to."
  [app proposal]
  (let [request (proposal->request proposal)
        thread (str "commit-" (or (:id proposal) "anon") "-" (random-uuid))
        state (g/invoke app {:request request :context actor-context}
                        {:thread-id thread})]
    (disposition->wire state thread)))

(defn proposal-status
  "What became of one reference.

  Answers \"unknown\" for a reference this process has never seen -- including one
  from before a restart, because the checkpointer is in memory. Reporting an
  unknown reference as still pending would be a guess dressed as a fact."
  [app reference]
  (if-let [checkpoint (g/get-state app reference)]
    (assoc (disposition->wire (:state checkpoint) reference)
           :resolved? (not= :interrupted (:status checkpoint)))
    {:status "unknown"
     :reference reference
     :detail "この reference は本プロセスに記録がありません（再起動で失われた可能性）"}))

(defn operator-decide!
  "Resume a pending proposal with the OPERATOR's decision.

  This is the only path that resumes the graph's approval interrupt, and it is
  reachable only from the operator listener. `by` is required and recorded: an
  approval nobody is named for cannot be audited, which is most of the reason the
  gate exists."
  [app reference {:keys [status by]}]
  (cond
    (not (contains? #{"approved" "rejected"} status))
    {:status "held"
     :refusal {:rule :malformed-decision
               :detail "status は approved か rejected でなければなりません"}}

    (or (nil? by) (and (string? by) (empty? by)))
    {:status "held"
     :refusal {:rule :approver-unnamed
               :detail "by（承認者）は必須です — 名前のない承認は監査できません"}}

    (nil? (g/get-state app reference))
    {:status "unknown" :reference reference
     :detail "この reference は本プロセスに記録がありません"}

    :else
    (let [state (g/invoke app {:approval {:status (keyword status) :by by}}
                          {:thread-id reference :resume? true})]
      (assoc (disposition->wire state reference) :decided-by by))))

(def consent-token-env
  "The consent surface's shared secret with whichever app holds the Passkey ceremony.

  Loopback binding was the only thing guarding /commit, and loopback is not an
  authorisation: every process on the host shares it. Without this, anything running
  locally could POST a proposal claiming a subject consented, and the only thing left
  between that and a provisioned line -- or a transferred one -- would be this actor's
  operator approving what they believed a human had agreed to.

  The governor is unaffected: it recomputes every ground-truth fact from recorded state.
  This gates WHO MAY CLAIM a subject consented, which is the one thing a governor cannot
  recompute, because consent happened somewhere else."
  "ESIM_CONSENT_TOKEN")

(def consent-token-header "X-ESIM-CONSENT-TOKEN")

(defn consent-token
  "The configured consent token, or nil when unset. Read at call time."
  []
  (let [t (System/getenv consent-token-env)]
    (when (and t (seq t)) t)))

(defn handler
  "The HttpHandler for the CONSENT surface: commit and read, never decide."
  [store app]
  (reify HttpHandler
    (handle [_ exchange]
      (try
        (let [method (.getRequestMethod ^HttpExchange exchange)
              path (.getPath (.getRequestURI ^HttpExchange exchange))
              expected (consent-token)
              presented (.getFirst (.getRequestHeaders ^HttpExchange exchange)
                                   consent-token-header)]
          (cond
            ;; /healthz stays open: it carries no subject data, and a deployment must be
            ;; able to ask whether this actor is up before it has a token to ask with.
            (and (= "GET" method) (= "/healthz" path))
            (send! exchange 200 {:status "ok"
                                 :actor (:actor-id actor-context)
                                 :euiccs (count (store/all-euiccs store))})

            ;; Everything else needs the token, and an UNSET token refuses rather than
            ;; waves through -- failing open would leave this surface most permissive
            ;; exactly where nobody had configured it.
            (nil? expected)
            (send! exchange 503
                   {:status "held"
                    :refusal {:rule :consent-surface-unconfigured
                              :detail (str consent-token-env
                                           " が未設定のため proposal を受け付けません")}})

            (not= expected presented)
            (send! exchange 401
                   {:status "held" :refusal {:rule :consent-token-mismatch}})

            ;; Read-only, so it is safe on the consent surface: a caller learns
            ;; what became of its own reference without being able to decide it.
            (and (= "GET" method) (re-matches #"/proposals/[^/]+" path))
            (send! exchange 200
                   (proposal-status app (subs path (count "/proposals/"))))

            (and (= "POST" method) (= "/commit" path))
            (let [body (json/read-str (read-body exchange) :key-fn keyword)
                  proposal (:proposal body)]
              (if-not (map? proposal)
                (send! exchange 400 {:status "held"
                                     :refusal {:rule :malformed-request
                                               :detail "proposal がありません"}})
                (send! exchange 200 (commit-outcome app proposal))))

            :else
            (send! exchange 404 {:status "held"
                                 :refusal {:rule :not-found :detail path}})))
        (catch Exception e
          ;; A failure here is still an outcome the caller must be able to record,
          ;; so it goes back as a refusal rather than as an empty 500 body.
          (send! exchange 500 {:status "held"
                               :refusal {:rule :actor-error
                                         :detail (str (.getMessage e))}}))))))

(def operator-token-env "ESIM_OPERATOR_TOKEN")

(defn- operator-token []
  (let [t (System/getenv operator-token-env)]
    (when (and t (seq t)) t)))

(defn operator-handler
  "The HttpHandler for the OPERATOR surface: decide, and nothing else.

  Refuses every request when ESIM_OPERATOR_TOKEN is unset. An unauthenticated
  decide endpoint is a way to approve a real line cut or a real number transfer,
  so the absent-configuration case fails closed rather than open -- the opposite
  choice would make the surface most dangerous exactly when nobody had configured
  it."
  [app]
  (reify HttpHandler
    (handle [_ exchange]
      (try
        (let [method (.getRequestMethod ^HttpExchange exchange)
              path (.getPath (.getRequestURI ^HttpExchange exchange))
              expected (operator-token)
              presented (.getFirst (.getRequestHeaders ^HttpExchange exchange)
                                   "X-ESIM-OPERATOR-TOKEN")]
          (cond
            (nil? expected)
            (send! exchange 503
                   {:status "held"
                    :refusal {:rule :operator-surface-unconfigured
                              :detail (str operator-token-env
                                           " が未設定のため decide を受け付けません")}})

            (not= expected presented)
            (send! exchange 401
                   {:status "held"
                    :refusal {:rule :operator-token-mismatch}})

            (and (= "POST" method)
                 (re-matches #"/proposals/[^/]+/decide" path))
            (let [reference (second (re-matches #"/proposals/([^/]+)/decide" path))
                  body (json/read-str (read-body exchange) :key-fn keyword)]
              (send! exchange 200 (operator-decide! app reference body)))

            :else
            (send! exchange 404 {:status "held"
                                 :refusal {:rule :not-found :detail path}})))
        (catch Exception e
          (send! exchange 500 {:status "held"
                               :refusal {:rule :actor-error
                                         :detail (str (.getMessage e))}}))))))

(defn- listener [port ^HttpHandler h]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" (int port)) 0)]
    ;; Loopback only, deliberately: neither surface has transport security of its
    ;; own. Binding either to a public interface before that exists would make the
    ;; consent boundary decorative.
    (.createContext server "/" h)
    (.setExecutor server nil)
    (.start server)
    server))

(defn start!
  "Start both surfaces. Returns {:consent server :operator server :store st :app app}
  so a caller (or a test) can stop them.

  The two listeners share one compiled graph -- and therefore one checkpointer --
  which is what lets the operator resume a thread the consent surface created.
  They do NOT share a port, which is what stops the consent surface resuming it
  itself."
  ([] (start! {}))
  ([{:keys [port operator-port store]
     :or {port 1339 operator-port 1340}}]
   (let [st (or store (store/mem-store))
         app (operation/build st)]
     {:store st
      :app app
      :consent (listener port (handler st app))
      :operator (listener operator-port (operator-handler app))})))

(defn stop!
  "Stop both listeners."
  [{:keys [consent operator]}]
  (when consent (.stop ^HttpServer consent 0))
  (when operator (.stop ^HttpServer operator 0)))

(defn -main [& args]
  (let [port (if-let [p (first args)] (parse-long p) 1339)
        operator-port (if-let [p (second args)] (parse-long p) (inc port))
        running (start! {:port port :operator-port operator-port})]
    (println "cloud-itonami-esim")
    (println (str "  consent  http://127.0.0.1:" port))
    (println "    POST /commit                 -- a consented proposal")
    (println "    GET  /proposals/<reference>  -- what became of one")
    (println "    GET  /healthz")
    (println (str "  operator http://127.0.0.1:" operator-port))
    (println "    POST /proposals/<reference>/decide")
    (println (str "         requires header X-ESIM-OPERATOR-TOKEN = $"
                  operator-token-env))
    (println)
    (if (System/getenv operator-token-env)
      (println "operator surface: token configured")
      (println (str "operator surface: " operator-token-env
                    " is UNSET, so every decide is refused (fail closed)")))
    (println)
    (println "The two surfaces are two listeners on purpose. A pending proposal")
    (println "awaits THIS actor's operator; if decide sat on the consent surface,")
    (println "the consent surface could approve its own proposals.")
    (.join (Thread/currentThread))
    running))
