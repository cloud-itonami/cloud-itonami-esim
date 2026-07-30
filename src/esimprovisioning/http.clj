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

  The store is a per-process MemStore, so a restart forgets. That is R0: the
  shared durable plane (ADR-2607300300 D4, gap 2) is a separate change, and
  pretending otherwise by persisting to a local file would give the appearance of
  durability without the cross-domain reach that matters."
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
                        {:thread-id thread})
        verdict (:verdict state)]
    (case (:disposition state)
      :commit
      {:status "committed"
       :record (merge {:op (str (:op request))}
                      (:payload (:record state)))}

      :escalate
      {:status "pending"
       :reference thread
       :reason (str (or (some-> (last (:audit state)) :reason) :actuation))
       :detail (str "この操作は " (:actor-id actor-context)
                    " 自身の operator 承認を必要とします（consent surface の "
                    "Passkey 同意は operator 承認ではありません）")}

      ;; :hold, or anything unexpected -- refuse rather than guess.
      {:status "held"
       :refusal {:rule (or (some-> (:violations verdict) first :rule) :held)
                 :violations (mapv :rule (:violations verdict))
                 :confidence (:confidence verdict)}})
    ;; Note: the ledger write already happened inside the graph's :commit / :hold
    ;; node. This function reports; it does not record.
    ))

(defn handler
  "The HttpHandler over one store + compiled actor."
  [store app]
  (reify HttpHandler
    (handle [_ exchange]
      (try
        (let [method (.getRequestMethod ^HttpExchange exchange)
              path (.getPath (.getRequestURI ^HttpExchange exchange))]
          (cond
            (and (= "GET" method) (= "/healthz" path))
            (send! exchange 200 {:status "ok"
                                 :actor (:actor-id actor-context)
                                 :euiccs (count (store/all-euiccs store))})

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

(defn start!
  "Start the surface. Returns the HttpServer so a caller (or a test) can stop it."
  ([] (start! {}))
  ([{:keys [port store] :or {port 1339}}]
   (let [st (or store (store/mem-store))
         app (operation/build st)
         server (HttpServer/create (InetSocketAddress. "127.0.0.1" (int port)) 0)]
     ;; Loopback only, deliberately: this surface accepts consented proposals and
     ;; has no authentication of its own yet. Binding it to a public interface
     ;; before that exists would make the consent boundary decorative.
     (.createContext server "/" (handler st app))
     (.setExecutor server nil)
     (.start server)
     server)))

(defn -main [& args]
  (let [port (if-let [p (first args)] (parse-long p) 1339)
        server (start! {:port port})]
    (println (str "cloud-itonami-esim listening on http://127.0.0.1:" port))
    (println "  POST /commit   -- a consented proposal; answers committed | held | pending")
    (println "  GET  /healthz")
    (println)
    (println "Every op a consent surface can send is absent from every phase's :auto")
    (println "set, so a well-formed proposal answers \"pending\": it awaits THIS")
    (println "actor's operator. That is the two gates, not a limitation.")
    (.join (Thread/currentThread))
    server))
