(ns esimprovisioning.store
  "The SSoT seam. `Store` is the whole surface the actor may touch, so the
  backend is a swap rather than a rewrite: `MemStore` today, a Datomic /
  kotoba-server backed store next.

  MemStore is the only implementation at R0 and it holds plain maps in an atom,
  so there is no EDN-blob codec and no `:db.unique/identity` schema to write --
  which is why `kotoba-lang/langchain-store` is not a dependency yet. It becomes
  one the moment a real backend is added; the point of langchain-store is that
  nobody hand-rolls `enc`/`dec` and the schema again, and this actor must not
  either.

  The ledger is append-only: `append-ledger!` only ever conjes. Every commit and
  every hold lands there, which is what makes 'who approved this transfer, and
  on what basis' answerable at all."
  (:require [kotoba.esim.lifecycle :as lifecycle]))

(defprotocol Store
  (euicc [s eid]
    "The registered eUICC record, or nil.")
  (all-euiccs [s]
    "Every registered eUICC, ordered by EID.")
  (profiles-of [s eid]
    "The profiles installed on this eUICC, as kotoba.esim/profile records.")
  (profile-of [s eid iccid]
    "One installed profile, or nil.")
  (subject-of [s iccid]
    "The subject (a did:key in this workspace) currently accountable for this
     profile, or nil.")
  (transfer-of [s iccid]
    "The pending or last-recorded ownership transfer for this profile, or nil.")
  (ledger [s]
    "The append-only audit ledger, oldest first.")
  (append-ledger! [s fact]
    "Append one audit fact. Never overwrites.")
  (apply-commit! [s record]
    "Apply a governor-cleared, human-approved commit record."))

;; ----------------------------- demo data -----------------------------

(def demo-eid "89049032000000000000000000000001")
(def demo-iccid-a "8981012345678901230")
(def demo-iccid-b "8981012345678909993")

(defn demo-data
  "A representative starting state: one registered consumer eUICC with one
  enabled profile and one disabled profile, both owned by the same subject.

  Fixtures, with provenance stated: the EID and ICCIDs are SYNTHETIC. The ICCIDs
  carry real ITU-T E.118 / Luhn check digits so that checksum validation is
  actually exercised rather than stubbed, but no real subscriber, operator or
  device is represented here."
  []
  {:euiccs {demo-eid {:esim/eid demo-eid :esim/variant :consumer
                      :esim/manufacturer "example-eum"}}
   :profiles {demo-eid [{:esim/iccid demo-iccid-a :esim/state :enabled
                         :esim/class :operational
                         :esim/provider-name "Example MNO"}
                        {:esim/iccid demo-iccid-b :esim/state :disabled
                         :esim/class :operational
                         :esim/provider-name "Other MNO"}]}
   :subjects {demo-iccid-a "did:key:zDemoSubjectA"
              demo-iccid-b "did:key:zDemoSubjectA"}
   :transfers {}
   :ledger []})

;; ----------------------------- MemStore -----------------------------

(defn- put-profiles [state eid profiles]
  (assoc-in state [:profiles eid] (vec profiles)))

(defrecord MemStore [a]
  Store
  (euicc [_ eid] (get-in @a [:euiccs eid]))
  (all-euiccs [_] (sort-by :esim/eid (vals (:euiccs @a))))
  (profiles-of [_ eid] (vec (get-in @a [:profiles eid])))
  (profile-of [_ eid iccid]
    (first (filter #(= iccid (:esim/iccid %)) (get-in @a [:profiles eid]))))
  (subject-of [_ iccid] (get-in @a [:subjects iccid]))
  (transfer-of [_ iccid] (get-in @a [:transfers iccid]))
  (ledger [_] (vec (:ledger @a)))
  (append-ledger! [_ fact] (swap! a update :ledger (fnil conj []) fact) fact)

  (apply-commit! [_ record]
    (let [{:keys [effect payload]} record]
      (case effect
        :euicc/registered
        (swap! a assoc-in [:euiccs (:eid payload)]
               {:esim/eid (:eid payload)
                :esim/variant (or (:variant payload) :consumer)
                :esim/manufacturer (:manufacturer payload)})

        :profile/installed
        (swap! a (fn [st]
                   (-> st
                       (put-profiles (:eid payload)
                                     (conj (vec (get-in st [:profiles (:eid payload)]))
                                           (:profile payload)))
                       (assoc-in [:subjects (:iccid payload)] (:subject payload)))))

        :profile/lifecycle-applied
        ;; The next state is NOT recomputed here -- the governor already cleared
        ;; it through kotoba.esim.lifecycle, and the cleared outcome carries the
        ;; updated profile vector. Recomputing would be a second opinion.
        (swap! a put-profiles (:eid payload)
               (get-in payload [:outcome :esim/profiles]))

        :smds/event-registered
        (swap! a assoc-in [:smds-events (:event-id payload)] payload)

        :ownership/transfer-requested
        ;; A REQUEST, not an executed transfer. The subject of record is
        ;; deliberately NOT reassigned here: execution is a licensed operator
        ;; act outside this actor (see README `Actuation`).
        (swap! a assoc-in [:transfers (:iccid payload)]
               (assoc payload :executed false))

        (throw (ex-info "unknown commit effect" {:effect effect})))
      record)))

(defn mem-store
  "A MemStore over `data` (default: `demo-data`)."
  ([] (mem-store (demo-data)))
  ([data] (->MemStore (atom data))))

;; ----------------------------- derived reads -----------------------------

(defn enabled-iccid
  "The ICCID of the currently enabled profile on this eUICC, or nil. Delegates
  to kotoba.esim.lifecycle rather than filtering here, so 'which profile is
  enabled' has one definition."
  [s eid]
  (first (lifecycle/enabled-iccids (profiles-of s eid))))
