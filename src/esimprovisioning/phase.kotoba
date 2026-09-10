(ns esimprovisioning.phase
  "Phase 0->3 staged rollout. Where `esimprovisioning.governor` answers 'is this
  allowed?', the phase answers 'how much autonomy does the actor have *yet*?'.
  It can only ever make the actor MORE conservative than the governor, never the
  reverse.

    Phase 0  read-only        -- reads only (still governor-gated).
    Phase 1  assisted-intake  -- eUICC registration allowed, every write needs
                                 human approval.
    Phase 2  + discovery      -- adds SM-DS event registration (still approval).
    Phase 3  supervised auto  -- governor-clean, high-confidence eUICC
                                 REGISTRATION may auto-commit. Nothing else.

  `:profile/download`, `:profile/lifecycle` and `:ownership/transfer` are
  deliberately ABSENT from every phase's `:auto` set, including phase 3. This is
  a permanent structural fact about this table, not a rollout milestone still to
  come:

  - a profile download or lifecycle transition changes whether a real person's
    line works;
  - an ownership transfer is the primary SIM-swap fraud path, and an attacker
    who reaches an auto-commit path for it has taken over a phone number, which
    is the second factor for everything else.

  `esimprovisioning.governor`'s `:actuation` high-stakes gate enforces the same
  invariant independently -- two layers, not one, agree on this.")

(def read-ops #{:coverage/report})

(def write-ops
  #{:euicc/register :smds/register :profile/download :profile/lifecycle
    :ownership/transfer})

;; NOTE the invariant: :profile/download, :profile/lifecycle and
;; :ownership/transfer are members of `write-ops` (governor-gated like any
;; write) but are NEVER a member of any phase's `:auto` set below. Do not add
;; them there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}."
  {0 {:label "read-only"       :writes #{}                                  :auto #{}}
   1 {:label "assisted-intake" :writes #{:euicc/register}                   :auto #{}}
   2 {:label "assisted-discovery"
      :writes #{:euicc/register :smds/register}                             :auto #{}}
   3 {:label "supervised-auto" :writes write-ops                            :auto #{:euicc/register}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - reads pass through unchanged (phase restricts autonomy, not reads).
  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - the three actuation ops are never auto-eligible at any phase, so they always
    escalate once the governor clears them (or hold if it does not)."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map an eSIM Provisioning Governor verdict to a base disposition before the
  phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
