(ns esimprovisioning.advisor
  "The contained intelligence node. `Advisor` is the whole surface the model
  reaches, so a mock and a real LLM are a swap rather than a rewrite.

  An advisor returns a PROPOSAL and nothing else. It cannot write, cannot
  actuate, and cannot mark its own proposal approved -- `esimprovisioning.governor`
  decides, and `esimprovisioning.phase` decides how much autonomy the actor has
  yet. Every proposal field is treated as untrusted input by the governor,
  including `:effect` and `:confidence`.

  The mock advisor exists so the whole graph is testable offline with no model,
  and so the governor's refusals can be exercised deliberately: it will happily
  emit a proposal the governor must reject, because an advisor that only ever
  proposes admissible things cannot test a censor."
  (:require [esimprovisioning.registry :as registry]))

(defprotocol Advisor
  (-advise [a store request]
    "Return a proposal map:
       {:effect kw            -- what this proposal would do, if admitted
        :value map            -- the payload
        :summary str          -- one line for the ledger
        :cites vector         -- what the advisor based this on
        :stake kw|nil         -- :actuation for anything that changes a real line
        :confidence double}"))

(def ^:private actuation-ops
  "Ops whose commit changes whether a real line works, or who controls it. The
  advisor marks these :actuation; the governor and every phase then require a
  human independently."
  #{:profile/download :profile/lifecycle :ownership/transfer})

(defn- stake-for [op]
  (when (contains? actuation-ops op) :actuation))

(defn mock-advisor
  "A deterministic advisor for offline runs and tests. It constructs its payload
  through `esimprovisioning.registry`, so a proposal that the records layer
  itself refuses never becomes a well-formed proposal."
  []
  (reify Advisor
    (-advise [_ _store {:keys [op subject value]}]
      (let [effect (get {:coverage/report    :coverage/reported
                         :euicc/register     :euicc/registered
                         :profile/download   :profile/installed
                         :profile/lifecycle  :profile/lifecycle-applied
                         :smds/register      :smds/event-registered
                         :ownership/transfer :ownership/transfer-requested}
                        op)]
        {:effect     effect
         :value      (merge {:eid subject} value)
         :summary    (str (name (or op :unknown)) " draft for " (registry/masked subject))
         :cites      [:store/recorded-state]
         :stake      (stake-for op)
         :confidence 0.9}))))

(defn trace
  "The ledger fact for one advisor inference -- what was asked, what came back.
  Identifiers are masked: the ledger is read by operators and must not be the
  place a device-bound identifier leaks."
  [request proposal]
  {:t          :advised
   :op         (:op request)
   :subject    (registry/masked (:subject request))
   :effect     (:effect proposal)
   :stake      (:stake proposal)
   :confidence (:confidence proposal)
   :summary    (:summary proposal)})
