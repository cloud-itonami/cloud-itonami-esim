(ns esimprovisioning.governor
  "eSIM Provisioning Governor -- the independent compliance layer that earns the
  eSIM Provisioning Advisor the right to commit. The LLM has no notion of whether
  an EID can structurally exist, whether an ICCID passes its own check digit,
  whether a profile transition is reachable from the state on record, whether
  enabling a profile would silently take away a working line, or when a request
  stops being a draft and becomes a real profile download / a real line cut / a
  real number takeover -- so this MUST be a separate system able to *reject* a
  proposal and fall back to HOLD.

  EVERY ground-truth check here is DELEGATED to `kotoba-lang/esim` and
  INDEPENDENTLY RECOMPUTED from the store, never read off the proposal:

    EID structure         -> kotoba.esim/validate-eid
    ICCID check digit     -> kotoba.esim/validate-iccid  (Luhn via kotoba.card)
    reachability          -> kotoba.esim.lifecycle/apply-operation
    single-enabled-profile-> kotoba.esim.lifecycle/enabled-conflict

  Nine checks, in priority order. The first seven are HARD violations: a human
  approver CANNOT override them (you do not get to approve your way past an
  identifier that cannot exist, a transition that is not reachable, or a
  self-transfer). The last two are SOFT: they ask a human to look (low
  confidence / actuation), and the human may approve -- but see
  `esimprovisioning.phase`: for `:stake :actuation` NO phase ever allows
  auto-commit either.

    1. Effect matches op        -- does the proposal's :effect match the ONE
                                   legitimate effect for the REQUEST's :op? Every
                                   check below keys off the REQUEST's :op, not the
                                   proposal's self-reported :effect. Without this
                                   first, an untrusted advisor could answer a
                                   harmless `:coverage/report` with
                                   `:effect :ownership/transfer-requested`, and a
                                   human approving what looks like a report would
                                   trigger a transfer with none of its scrutiny run.
    2. EID invalid              -- structurally impossible eUICC identifier.
    3. ICCID invalid            -- check digit failure.
    4. eUICC unknown            -- cannot install onto, or transition a profile of,
                                   an eUICC that was never registered.
    5. Transition unreachable   -- for `:profile/lifecycle`, recomputed over the
                                   eUICC's OWN recorded profiles.
    6. Enable would displace    -- for an :enable, another profile is already
                                   enabled. An eUICC has at most one enabled
                                   profile, so this would cut a working line as a
                                   side effect. Refused, not silently accepted.
    7. Transfer invalid         -- for `:ownership/transfer`, the recorded subject
                                   must match the claimed from-subject and differ
                                   from the to-subject, and the profile must exist.
    8. Low confidence           -- SOFT: below the floor, a human looks.
    9. Actuation                -- SOFT: `:stake :actuation` always escalates.

  There is deliberately NO per-jurisdiction spec-basis catalog at R0. The sibling
  card-issuing actor has one (`cardissuing.facts`), and the analogous thing here
  would be telecom licensing plus GSMA SAS-SM accreditation -- but seeding it
  would mean asserting jurisdiction-specific requirements this build has not
  independently verified, which is exactly the failure
  `cloud-itonami-isic-6120`'s ADR-0002 records (fabricated Radio Act / FCC /
  Ofcom / TKG citations). An empty catalog that holds is honest; an invented one
  is not. See README `Coverage`."
  (:require [kotoba.esim :as esim]
            [kotoba.esim.lifecycle :as lifecycle]
            [esimprovisioning.store :as store]))

(def confidence-floor 0.75)

(def op->effect
  "The ONE legitimate effect for each op. An allowlist, so a future op defaults
  to illegal until deliberately wired here."
  {:coverage/report     :coverage/reported
   :euicc/register      :euicc/registered
   :profile/download    :profile/installed
   :profile/lifecycle   :profile/lifecycle-applied
   :smds/register       :smds/event-registered
   :ownership/transfer  :ownership/transfer-requested})

(def high-stakes
  "Stakes that always require a human, whatever the confidence."
  #{:actuation})

(defn- effect-mismatch-violations [{:keys [op]} proposal]
  (let [expected (get op->effect op)]
    (when (not= expected (:effect proposal))
      [{:rule :effect-mismatch
        :detail (str "op " op " の正当な effect は " expected
                     " だが proposal は " (:effect proposal) " を主張している")}])))

(defn- eid-violations [{:keys [op subject]} proposal]
  (when (contains? #{:euicc/register :profile/download :smds/register} op)
    (let [eid (or (get-in proposal [:value :eid]) subject)
          issues (esim/validate-eid eid)]
      (when (seq issues)
        [{:rule :eid-invalid
          :detail (str "EID が構造的に不正: "
                       (pr-str (mapv :esim/issue issues)))}]))))

(defn- iccid-violations [{:keys [op]} proposal]
  (when (contains? #{:profile/download :profile/lifecycle :ownership/transfer} op)
    (let [iccid (get-in proposal [:value :iccid])
          issues (esim/validate-iccid iccid)]
      (when (seq issues)
        [{:rule :iccid-invalid
          :detail (str "ICCID が不正（E.118 チェックディジット含む）: "
                       (pr-str (mapv :esim/issue issues)))}]))))

(defn- euicc-unknown-violations [{:keys [op]} proposal st]
  (when (contains? #{:profile/download :profile/lifecycle} op)
    (let [eid (get-in proposal [:value :eid])]
      (when-not (store/euicc st eid)
        [{:rule :euicc-unknown
          :detail "未登録の eUICC に対する操作は許可されない（先に :euicc/register）"}]))))

(defn cleared-transition
  "The outcome kotoba.esim.lifecycle computes for a :profile/lifecycle proposal,
  over the eUICC's OWN recorded profiles. Exposed because the STORE must apply
  what the GOVERNOR computed, never the profile vector an advisor claimed -- the
  advisor is untrusted input, and letting its payload reach the store would make
  the reachability check decorative."
  [{:keys [op]} proposal st]
  (when (= op :profile/lifecycle)
    (let [{:keys [eid iccid operation]} (:value proposal)]
      (lifecycle/apply-operation (store/profiles-of st eid) iccid operation))))

(defn- transition-violations [request proposal st]
  (when (= (:op request) :profile/lifecycle)
    (let [outcome (cleared-transition request proposal st)]
      (when-not (:esim/ok? outcome)
        (let [kinds (set (map :esim/issue (:esim/issues outcome)))]
          ;; :enable/would-displace is reported as its own rule so the ledger
          ;; distinguishes 'not reachable' from 'would cut a working line'.
          (if (contains? kinds :enable/would-displace)
            [{:rule :enable-would-displace
              :detail (str "既に enabled な profile があるため enable は拒否: "
                           (pr-str (:esim/issues outcome)))}]
            [{:rule :transition-unreachable
              :detail (str "記録上の状態から到達できない遷移: "
                           (pr-str (:esim/issues outcome)))}]))))))

(defn- transfer-violations [{:keys [op]} proposal st]
  (when (= op :ownership/transfer)
    (let [{:keys [iccid from-subject to-subject]} (:value proposal)
          recorded (store/subject-of st iccid)]
      (cond-> []
        (nil? recorded)
        (conj {:rule :transfer-subject-unknown
               :detail "この profile に記録上の subject が無い"})
        (and recorded (not= recorded from-subject))
        (conj {:rule :transfer-from-mismatch
               :detail (str "記録上の subject は " recorded
                            " で、申告された from-subject " from-subject " と一致しない")})
        (= from-subject to-subject)
        (conj {:rule :transfer-self
               :detail "同一 subject への transfer は、起きていない変更を台帳に書くことになる"})))))

(defn check
  "Return a verdict for one proposal. `st` is the Store -- every ground-truth
  check reads from it rather than from the proposal."
  [request context proposal st]
  (let [hard (vec (concat (effect-mismatch-violations request proposal)
                          (eid-violations request proposal)
                          (iccid-violations request proposal)
                          (euicc-unknown-violations request proposal st)
                          (transition-violations request proposal st)
                          (transfer-violations request proposal st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))
        ;; Only ever attach an outcome the governor itself cleared.
        cleared (when-not hard?
                  (let [o (cleared-transition request proposal st)]
                    (when (:esim/ok? o) o)))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?
     :cleared      cleared
     :actor        (:actor-id context)}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t           :held
   :op          (:op request)
   :actor       (:actor-id context)
   :subject     (:subject request)
   :disposition :hold
   :violations  (mapv :rule (:violations verdict))
   :confidence  (:confidence verdict)})
