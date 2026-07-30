(ns esimprovisioning.operation
  "OperationActor -- one eSIM provisioning operation = one supervised actor run,
  expressed as a langgraph-clj StateGraph. The advisor is sealed into a single
  node (:advise); its proposal is ALWAYS routed through the eSIM Provisioning
  Governor (:govern) and the rollout phase gate (:decide) before anything
  commits to the SSoT.

  Everything the actor depends on is injected, so each is a swap, not a rewrite:
    - the Store    (MemStore today; Datomic/kotoba-server is the next seam)
    - the Advisor  (mock | real LLM)                     - :advisor opt
    - the Phase    (0->3 rollout)                        - :phase in ctx

  One graph run = one operation (intake -> advise -> govern -> decide ->
  commit | hold | approval). No unbounded inner loop -- each operation is
  auditable and checkpointed.

  Human-in-the-loop = real approval workflow: `interrupt-before
  #{:request-approval}` pauses the actor and hands the decision to a human
  operator. The approver resumes with `{:approval {:status :approved :by \"...\"}}`
  (or :rejected). `:profile/download`, `:profile/lifecycle` and
  `:ownership/transfer` ALWAYS reach this node when the governor is clean -- see
  `esimprovisioning.phase`."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [esimprovisioning.advisor :as advisor]
            [esimprovisioning.governor :as governor]
            [esimprovisioning.phase :as phase]
            [esimprovisioning.store :as store]))

(defn- commit-fact
  "The ledger fact for a committed op. `approval` is the resumed human decision
  when this commit came via :request-approval (nil for an auto-commit, e.g.
  phase 3's :euicc/register) -- without it the ledger cannot answer 'approved by
  whom', which is the whole reason an ownership transfer is gated."
  [request context proposal approval]
  {:t           :committed
   :op          (:op request)
   :actor       (:actor-id context)
   :subject     (:subject request)
   :disposition :commit
   :basis       (:cites proposal)
   :summary     (:summary proposal)
   :approved-by (:by approval)})

(defn- commit-record
  "The record the store applies.

  The payload comes from the proposal, but any state TRANSITION comes from the
  verdict's `:cleared` outcome -- what the governor computed over recorded state,
  not what the advisor claimed. An advisor-supplied profile vector reaching the
  store would make the reachability check decorative."
  [request _context proposal verdict]
  {:effect  (:effect proposal)
   :path    [(:subject request)]
   :payload (cond-> (:value proposal)
              (:cleared verdict) (assoc :outcome (:cleared verdict)))})

(defn build
  "Compile an OperationActor graph bound to `store` (any
  `esimprovisioning.store/Store`).
  opts:
    :advisor      -- an `esimprovisioning.advisor/Advisor` (default: mock-advisor)
    :checkpointer -- langgraph checkpointer (default: in-mem)"
  [store & [{:keys [advisor checkpointer]
             :or   {advisor      (advisor/mock-advisor)
                    checkpointer (cp/mem-checkpointer)}}]]
  (-> (g/state-graph
       {:channels
        {:request     {:default nil}
         :context     {:default nil}   ; injected actor-id/role/phase
         :proposal    {:default nil}
         :verdict     {:default nil}
         :disposition {:default nil}   ; :commit | :hold | :escalate
         :record      {:default nil}
         :approval    {:default nil}
         :audit       {:reducer into :default []}}})

      (g/add-node :intake (fn [s] s))

      ;; eSIM Provisioning Advisor inference (the contained node) -- proposal only.
      ;;
      ;; The trace goes to the LEDGER as well as the run's :audit channel. The
      ;; channel is checkpointed, but the checkpointer here is in-memory and
      ;; per-thread, whereas the ledger is the durable record -- and what an
      ;; auditor most wants about a HELD proposal is what was actually proposed,
      ;; which the hold fact alone (rules + confidence) does not carry.
      ;; Identifiers in the trace are masked; see `advisor/trace`.
      (g/add-node :advise
        (fn [{:keys [request]}]
          (let [p (advisor/-advise advisor store request)
                t (advisor/trace request p)]
            (store/append-ledger! store t)
            {:proposal p :audit [t]})))

      ;; eSIM Provisioning Governor -- independent censor, separate from the LLM.
      (g/add-node :govern
        (fn [{:keys [request context proposal]}]
          {:verdict (governor/check request context proposal store)}))

      ;; Decide: governor disposition, then the rollout-phase gate (which can
      ;; only add caution). HARD governor violations -> HOLD (no override).
      (g/add-node :decide
        (fn [{:keys [request context proposal verdict]}]
          (let [base (phase/verdict->disposition verdict)
                ph   (:phase context phase/default-phase)
                {:keys [disposition reason]} (phase/gate ph request base)]
            (case disposition
              :hold
              {:disposition :hold
               :audit [(cond-> (governor/hold-fact request context verdict)
                         reason (assoc :phase-reason reason :phase ph))]}

              :escalate
              {:disposition :escalate
               :audit [{:t :approval-requested
                        :op (:op request) :subject (:subject request)
                        :reason (or reason
                                    (cond (:high-stakes? verdict) :actuation
                                          :else :low-confidence))
                        :phase ph
                        :confidence (:confidence verdict)}]}

              :commit
              {:disposition :commit
               :record (commit-record request context proposal verdict)}))))

      ;; Approval handoff -- paused by interrupt-before; a human operator resumes
      ;; with :approval. Then route commit/hold.
      (g/add-node :request-approval
        (fn [{:keys [request context proposal approval verdict]}]
          (if (= :approved (:status approval))
            {:disposition :commit
             :record (commit-record request context proposal verdict)}
            {:disposition :hold
             :audit [{:t :approval-rejected
                      :op (:op request)
                      :actor (:actor-id context)
                      :subject (:subject request)
                      :by (:by approval)}]})))

      (g/add-node :commit
        (fn [{:keys [request context proposal record approval]}]
          (store/apply-commit! store record)
          (let [fact (commit-fact request context proposal approval)]
            (store/append-ledger! store fact)
            {:audit [fact]})))

      (g/add-node :hold
        (fn [{:keys [audit]}]
          ;; The hold fact was built at :decide (or :request-approval) so the
          ;; reason travels with it; the ledger write happens here so both paths
          ;; land exactly one fact.
          (doseq [f audit
                  :when (contains? #{:held :approval-rejected} (:t f))]
            (store/append-ledger! store f))
          {}))

      (g/set-entry-point :intake)
      (g/add-edge :intake :advise)
      (g/add-edge :advise :govern)
      (g/add-edge :govern :decide)

      (g/add-conditional-edges :decide
        (fn [{:keys [disposition]}]
          (case disposition
            :commit   :commit
            :escalate :request-approval
            :hold)))

      (g/add-conditional-edges :request-approval
        (fn [{:keys [disposition]}]
          (if (= :commit disposition) :commit :hold)))

      (g/set-finish-point :commit)
      (g/set-finish-point :hold)

      (g/compile-graph
       {:checkpointer     checkpointer
        :interrupt-before #{:request-approval}})))
