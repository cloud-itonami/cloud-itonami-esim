(ns esimprovisioning.registry-test
  "The registry's contract: it constructs records, and every identifier or
  reachability fact it states comes from kotoba-lang/esim rather than from a
  local reimplementation."
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.esim :as esim]
            [kotoba.esim.export :as esim-export]
            [esimprovisioning.registry :as registry]
            [esimprovisioning.store :as store]))

(def eid store/demo-eid)
(def iccid-a store/demo-iccid-a)
(def iccid-b store/demo-iccid-b)
(def iccid-bad "8981012345678901231")

(deftest euicc-registration
  (testing "a valid EID yields an immutable draft"
    (let [r (registry/register-euicc eid)]
      (is (= eid (get-in r ["record" "eid"])))
      (is (true? (get-in r ["record" "immutable"])))
      (is (= "consumer" (get-in r ["record" "variant"])))))
  (testing "an invalid EID yields the LIBRARY's issues, not a record"
    (let [r (registry/register-euicc "123")]
      (is (nil? (get r "record")))
      (is (contains? (set (get r "issues")) "eid/wrong-length"))))
  (testing "an unsupported variant is refused rather than defaulted"
    (is (nil? (get (registry/register-euicc eid :variant :satellite) "record")))))

(deftest profile-installation
  (testing "an installed profile lands :disabled -- enabling is a separate decision"
    (let [r (registry/register-profile-installation eid iccid-b
                                                    :provider-name "Example MNO")]
      (is (= "disabled" (get-in r ["record" "state"])))
      (is (= eid (get-in r ["record" "eid"])) "the EID must be the digits, not a map")
      (is (= iccid-b (get-in r ["record" "iccid"])))))
  (testing "a bad check digit is refused with the library's own issue"
    (let [r (registry/register-profile-installation eid iccid-bad)]
      (is (nil? (get r "record")))
      (is (contains? (set (get r "issues")) "iccid/check-digit-failed"))))
  (testing "MSISDN is canonicalized by the library, not stored as given"
    (let [r (registry/register-profile-installation eid iccid-b
                                                    :msisdn "0081 9012345678")]
      (is (= "+819012345678" (get-in r ["record" "msisdn"]))))))

(deftest lifecycle-events-delegate-reachability
  (let [profiles (store/profiles-of (store/mem-store) eid)]
    (testing "a reachable operation yields a draft carrying from/to"
      (let [r (registry/register-lifecycle-event profiles iccid-a :disable)]
        (is (= "enabled" (get-in r ["record" "from"])))
        (is (= "disabled" (get-in r ["record" "to"])))
        (is (true? (get-in r ["record" "immutable"])))))
    (testing "enabling a second profile is refused, carrying the library's reason"
      (let [r (registry/register-lifecycle-event profiles iccid-b :enable)]
        (is (nil? (get r "record")))
        (is (contains? (set (get r "issues")) "enable/would-displace"))))
    (testing "an unknown profile is refused"
      (let [r (registry/register-lifecycle-event profiles "8981077777777777776" :enable)]
        (is (nil? (get r "record")))
        (is (contains? (set (get r "issues")) "profile/not-found"))))))

(deftest smds-registration-is-consumer-only
  (testing "consumer yields a draft"
    (is (some? (get (registry/register-smds-event eid "evt-1" "smdp.example.com")
                    "record"))))
  (testing "m2m is refused with the reason, because SGP.02 has no discovery server"
    (let [r (registry/register-smds-event eid "evt-1" "smdp.example.com" :variant :m2m)]
      (is (nil? (get r "record")))
      (is (= ["m2m-has-no-discovery-server"] (get r "issues"))))))

(deftest ownership-transfer-is-a-request
  (testing "a valid transfer draft is explicitly not executed"
    (let [r (registry/register-ownership-transfer iccid-a "did:key:zA" "did:key:zB")]
      (is (false? (get-in r ["record" "executed"])))
      (is (= "did:key:zB" (get-in r ["record" "to_subject"])))))
  (testing "a self-transfer is refused by the library"
    (let [r (registry/register-ownership-transfer iccid-a "did:key:zA" "did:key:zA")]
      (is (nil? (get r "record")))
      (is (= ["transfer-rejected"] (get r "issues"))))))

(deftest masking-is-the-library-function-not-a-copy
  (testing "registry/masked IS kotoba.esim.export/mask-identifier -- if this ever
            becomes a separate implementation, the two can drift and the drift
            leaks the identifier masking was supposed to hide"
    (is (identical? esim-export/mask-identifier registry/masked)))
  (is (= "...1230" (registry/masked iccid-a)))
  (is (= "" (registry/masked nil))))

(deftest fixtures-are-synthetic-but-really-valid
  (testing "the demo identifiers pass the library's own structural checks, so
            checksum validation is exercised rather than stubbed"
    (is (esim/eid-valid? eid))
    (is (esim/iccid-valid? iccid-a))
    (is (esim/iccid-valid? iccid-b))
    (is (not (esim/iccid-valid? iccid-bad)))))
