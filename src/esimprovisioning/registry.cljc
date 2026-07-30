(ns esimprovisioning.registry
  "Pure record construction for eSIM provisioning drafts: eUICC registration,
  profile installation, profile-lifecycle events, SM-DS event registration and
  ownership-transfer requests.

  Every identifier fact this namespace states is DELEGATED to
  `kotoba-lang/esim`, never recomputed here:

    EID structure           -> kotoba.esim/eid-valid?     (32 digits, telecom MII)
    ICCID check digit       -> kotoba.esim/iccid-valid?   (E.118, Luhn via kotoba.card)
    profile reachability    -> kotoba.esim.lifecycle      (the state machine)

  This is deliberate. The sibling `cloud-itonami-card-issuing` actor
  reimplemented `luhn-check-digit`/`luhn-valid?` locally even though
  `kotoba.card/luhn-valid?` existed, which is how two copies of one checksum
  came to exist; and its lifecycle allowlist lived only in its governor, so the
  library had to be corrected to mirror it after the fact. Here the library is
  the single authority from the start and this actor is a consumer of it.

  Pure data + pure functions -- no I/O, no network call to any SM-DP+, SM-DS or
  regulator. It builds the RECORD an eSIM provisioning operator would keep, not
  the act of provisioning (that is `esimprovisioning.operation`, always
  human-gated -- see README)."
  (:require [kotoba.esim :as esim]
            [kotoba.esim.export :as esim-export]
            [kotoba.esim.lifecycle :as lifecycle]))

(defn- immutable-record [m]
  (assoc m "immutable" true))

(defn- issue-name
  "Render an issue keyword with its namespace intact: :iccid/check-digit-failed
  -> \"iccid/check-digit-failed\". `name` alone would drop the namespace, and the
  namespace is the part that says WHICH identifier or transition failed."
  [issue]
  (subs (str issue) 1))

(defn register-euicc
  "Append-only eUICC registration draft. Refuses a structurally invalid EID --
  `kotoba.esim/validate-eid`'s issues are returned rather than a record, so a
  caller cannot get a draft for an identifier that cannot exist."
  [eid & {:keys [variant manufacturer] :or {variant :consumer}}]
  (let [issues (esim/validate-eid eid)]
    (if (seq issues)
      {"issues" (mapv (comp issue-name :esim/issue) issues)}
      (if-let [e (esim/euicc eid :variant variant :manufacturer manufacturer)]
        {"record" (immutable-record
                   {"record_id" (str "EUICC-" (:esim/eid e))
                    "kind" "euicc-registration-draft"
                    "eid" (:esim/eid e)
                    "variant" (name (:esim/variant e))
                    "manufacturer" manufacturer})}
        {"issues" ["variant-unsupported"]}))))

(defn register-profile-installation
  "Append-only profile-installation draft for an eUICC. The profile lands in
  :disabled, which is what an installed-but-not-enabled profile is -- this
  namespace does not enable it, because enabling is a separate lifecycle
  decision with its own gate."
  [eid iccid & {:keys [msisdn provider-name smdp-fqdn]}]
  (let [issues (into (esim/validate-eid eid) (esim/validate-iccid iccid))]
    (if (seq issues)
      {"issues" (mapv (comp issue-name :esim/issue) issues)}
      (if-let [p (esim/profile iccid :disabled
                               :msisdn msisdn
                               :provider-name provider-name
                               :smdp-fqdn smdp-fqdn)]
        {"record" (immutable-record
                   {"record_id" (str "PROFILE-" (:esim/iccid p))
                    "kind" "profile-installation-draft"
                    "eid" (:esim/eid (esim/euicc eid)) ; validated above
                    "iccid" (:esim/iccid p)
                    "state" (name (:esim/state p))
                    "msisdn" (:esim/msisdn p)
                    "provider_name" (:esim/provider-name p)
                    "smdp_fqdn" (:esim/smdp-fqdn p)})
         "profile" p}
        {"issues" ["profile-rejected"]}))))

(defn register-lifecycle-event
  "Append-only profile-lifecycle draft. Reachability is decided by
  `kotoba.esim.lifecycle/apply-operation` over the eUICC's OWN recorded
  profiles -- this namespace neither restates the transition table nor trusts a
  caller's claim about the current state.

  Returns {\"record\" .. \"outcome\" ..} when admitted, or {\"issues\" [..]}
  carrying the library's own refusal reasons."
  [profiles iccid operation]
  (let [outcome (lifecycle/apply-operation profiles iccid operation)]
    (if (:esim/ok? outcome)
      {"record" (immutable-record
                 {"record_id" (str iccid "#" (name operation))
                  "kind" "profile-lifecycle-draft"
                  "iccid" iccid
                  "operation" (name operation)
                  "from" (name (:esim/from outcome))
                  "to" (name (:esim/to outcome))})
       "outcome" outcome}
      {"issues" (mapv (comp issue-name :esim/issue) (:esim/issues outcome))
       "outcome" outcome})))

(defn register-smds-event
  "Append-only SM-DS event-registration draft. Consumer (SGP.22) only --
  `kotoba.esim/event-registration` returns nil for :m2m because SGP.02 has no
  discovery server, and that refusal is surfaced rather than worked around."
  [eid event-id smdp-fqdn & {:keys [variant] :or {variant :consumer}}]
  (if-let [r (esim/event-registration eid event-id smdp-fqdn :variant variant)]
    {"record" (immutable-record
               {"record_id" (str "SMDS-" (:esim/event-id r))
                "kind" "smds-event-registration-draft"
                "eid" (:esim/eid r)
                "event_id" (:esim/event-id r)
                "smdp_fqdn" (:esim/smdp-fqdn r)
                "variant" (name (:esim/variant r))})}
    {"issues" [(if (= :consumer variant)
                 "event-registration-rejected"
                 "m2m-has-no-discovery-server")]}))

(defn register-ownership-transfer
  "Append-only ownership-transfer REQUEST draft. Never an executed transfer:
  transfer is the primary SIM-swap fraud path, so it is always a human decision
  (`esimprovisioning.governor` marks it :actuation and no phase auto-commits it).

  `kotoba.esim/ownership-transfer` refuses a self-transfer, which would be an
  audit entry asserting a change that did not happen."
  [iccid from-subject to-subject & {:keys [reason]}]
  (if-let [t (esim/ownership-transfer iccid from-subject to-subject :reason reason)]
    {"record" (immutable-record
               {"record_id" (str "XFER-" (:esim/iccid t) "-" (:esim/to-subject t))
                "kind" "ownership-transfer-request-draft"
                "iccid" (:esim/iccid t)
                "from_subject" (:esim/from-subject t)
                "to_subject" (:esim/to-subject t)
                "reason" reason
                "executed" false})}
    {"issues" ["transfer-rejected"]}))

(def masked
  "Never persist/log/print a full EID or ICCID outside the operator's own
  boundary. This is `kotoba.esim.export/mask-identifier` itself, aliased rather
  than reimplemented -- writing the masking rule again here is how two copies of
  one rule start, and a masking rule that drifts leaks the identifier it was
  supposed to hide."
  esim-export/mask-identifier)
