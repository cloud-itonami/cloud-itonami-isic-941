(ns membershipassocorg.sim
  "Demo driver -- `clojure -M:dev:run`. Walks the membership-association
  administrative coordination actor through a clean phase-3 auto-commit,
  an always-escalate safety-concern flag (human approves), a phase-1
  supply-request escalation (human rejects), and a hard-hold
  (unverified member), then prints the resulting audit ledger and
  coordination log. Mirrors `cerealops.sim` (cloud-itonami-isic-0111)."
  (:require [langgraph.graph :as g]
            [membershipassocorg.operation :as operation]
            [membershipassocorg.store :as store]))

(def coordinator {:actor-id "coordinator-01" :role :association-staff :phase 3})
(def coordinator-phase-1 {:actor-id "coordinator-01" :role :association-staff :phase 1})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "ops-director-01"}}
          {:thread-id tid :resume? true}))

(defn- reject! [actor tid]
  (g/run* actor {:approval {:status :rejected :by "ops-director-01"}}
          {:thread-id tid :resume? true}))

(defn demo
  "Run the compiled StateGraph through a commit path, an
  escalate->approve->commit path, an escalate->reject->hold path, and
  a hard-hold path; print each result and the final audit ledger +
  coordination log."
  []
  (let [st (store/make-store)
        actor (operation/build st)]

    (println "=== Membership Association Coordination Actor Demo ===")

    (println "\n== schedule-member-event member-1 (phase-3, governor-clean -> commit) ==")
    (println (exec-op actor "t1"
                      {:operation :schedule-member-event :member-id "member-1"
                       :event-id "event-1" :effect :propose}
                      coordinator))

    (println "\n== flag-safety-concern member-1 (ALWAYS escalates -- director approves) ==")
    (let [r (exec-op actor "t2"
                     {:operation :flag-safety-concern :member-id "member-1"
                      :concern-type "member-conduct-issue"
                      :description "Member conduct concern for review"
                      :effect :propose}
                     coordinator)]
      (println r)
      (println "-- ops director approves --")
      (println (approve! actor "t2")))

    (println "\n== coordinate-supply-request (phase-1, not yet auto-commit -> escalate -- director rejects) ==")
    (let [r (exec-op actor "t3"
                     {:operation :coordinate-supply-request :member-id "member-1"
                      :description "Order name badges for annual gala" :effect :propose}
                     coordinator-phase-1)]
      (println r)
      (println "-- ops director rejects --")
      (println (reject! actor "t3")))

    (println "\n== schedule-member-event member-3 (unverified -> HARD hold, no interrupt) ==")
    (println (exec-op actor "t4"
                      {:operation :schedule-member-event :member-id "member-3"
                       :event-id "event-1" :effect :propose}
                      coordinator))

    (println "\n== audit ledger ==")
    (doseq [f (store/ledger st)] (println f))

    (println "\n== coordination log (committed records) ==")
    (doseq [r (store/coordination-log st)] (println r))

    {:ledger (store/ledger st) :coordination-log (store/coordination-log st)}))

(defn -main
  "clojure -M:run entrypoint."
  [& _args]
  (demo))

(comment
  ;; In a real REPL:
  (demo)
  )
