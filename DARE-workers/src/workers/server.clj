(ns workers.server
  (:use gloss.core gloss.io lamina.core clojure.tools.cli)
  (:require [aleph.tcp :as tcp]
            [lamina.connections :as c]
            [clojure.contrib.json :as json]
            [somnium.congomongo :as mongo]
            [clojure.contrib.logging :as log]
            [clj-stacktrace.repl :as stacktrace])
  (:import [java.util concurrent.Executors UUID]
           [java.net InetSocketAddress InetAddress NetworkInterface]
           [es.uvigo.ei.sing.dare.util XMLUtil]
           [es.uvigo.ei.sing.stringeditor Util XMLInputOutput])
  (:gen-class))


(defn adapt-updated-fields [collection next-execution-ms fields]
  (case collection
    :periodical-executions (assoc {:lastExecution
                                   (assoc fields :creationTime
                                          (System/currentTimeMillis))}
                             :next-execution-ms next-execution-ms
                             :scheduled false
                             :execution-sent-at nil)
    fields))

(defn db-update! [collection code mongo-updates]
  (mongo/update! collection {:_id code} mongo-updates :upsert false))

(defn db-execution-completed! [collection code next-execution-ms & {:as updated-fields}]
  (db-update! collection code
              {:$set (adapt-updated-fields
                      collection next-execution-ms updated-fields)
               :$unset {:error 1}}))

(defn db-execution-error! [collection code fields-to-update]
  (db-update! collection code {:$set fields-to-update}))

(defn exception-message [ex]
  (stacktrace/pst-str ex))

(defn millis-elapsed-since [& times]
  (let [now (System/currentTimeMillis)]
    (map #(- now %) times)))

(def ^{:dynamic true} *time-allowed-for-execution-ms* (* 1 60 1000))

(def current-petitions (agent 0 :error-mode :continue))

(defn with-decrease-petitions-number [f]
  (fn [& args]
    (try
      (apply f args)
      (finally
       (send current-petitions dec)))))

(defn with-timeout-handling [f on-timeout]
  (fn [& args]
    (let [start (System/currentTimeMillis)
          success (constant-channel)
          timeout-ms *time-allowed-for-execution-ms*
          thread (Thread/currentThread)
          timeout-pipeline (run-pipeline nil
                             (fn [_]
                               (poll {:success success} timeout-ms))
                             read-channel
                             (fn [result]
                               (when-not result
                                 (.interrupt thread))))]
      (try
        (let [on-success (apply f args)]
          (enqueue success true)
          (on-success))
        (catch InterruptedException e
          (if (< (first (millis-elapsed-since start)) timeout-ms)
            (throw e)
            (on-timeout
             (str "The execution took more than the maximum allowed: " timeout-ms " ms"))))
        (finally
         (enqueue success true)
         (try
           (wait-for-result timeout-pipeline)
           (catch InterruptedException e
             ;; it can happen if the thread has been interrupted,
             ;; .interrupt has already been reached
             )
           (finally
            ;; clear possible interrupted flag
            (Thread/interrupted))))))))

(defn with-error-handling [f on-error]
  (fn [& args]
    (try
      (apply f args)
      (catch Throwable e
        (on-error e)))))

(defn check-for-interruption [x]
  (when (Thread/interrupted)
    (throw (InterruptedException.)))
  x)

(defn execute-robot [robotXML inputs]
  (-> (XMLUtil/toDocument robotXML)
      (check-for-interruption)
      (XMLInputOutput/loadTransformer)
      (check-for-interruption)
      (Util/runRobot (into-array String inputs))))

(defn callable-execution
  [{:keys [inputs robotXML result-code periodical-code next-execution-ms]}]
  {:pre [(sequential? inputs) (string? robotXML)
         (or result-code (and periodical-code next-execution-ms))
         (not (and result-code periodical-code))]}
  (let [submit-time (System/currentTimeMillis)
        is-periodical periodical-code
        code (or result-code periodical-code)
        collection-to-update (if is-periodical
                               :periodical-executions
                               :executions)
        name (str "["(when periodical-code "periodical") "execution " code "]")

        on-error (fn [type message]
                   (let [mark-as-not-scheduled
                         (when is-periodical
                           {:scheduled false})
                         delay-next-execution
                         (when is-periodical
                           {:next-execution-ms (+ next-execution-ms 3600)})]
                     (db-execution-error! collection-to-update
                                          code
                                          (merge
                                           {:error {:type type
                                                    :message message}}
                                            mark-as-not-scheduled
                                           delay-next-execution))))
        on-exception (fn [ex]
                       (log/error (str "Error executing " name) ex)
                       (on-error :error (exception-message ex)))]
    [name
     (->
      (fn []
        (let [start-execution-time (System/currentTimeMillis)
              result-array (execute-robot robotXML inputs)
              [all-time real-execution-time] (millis-elapsed-since
                                              submit-time start-execution-time)
              next-execution-ms (+ all-time (or next-execution-ms 0))]
          (fn []
            (db-execution-completed! collection-to-update
                                     code
                                     next-execution-ms
                                     :resultLines (seq result-array)
                                     :executionTimeMilliseconds all-time
                                     :realExecutionTime real-execution-time)
            (log/info (str "execution completed for: " name)))))
      (with-timeout-handling (partial on-error :timeout))
      (with-error-handling on-exception)
      (with-decrease-petitions-number))]))

(def automator-executor)

(defn accept-request [execution-wrapper request]
  (let [[name execution] (callable-execution request)]
    (.submit automator-executor (execution-wrapper execution))
    (log/info (str name " accepted"))))

(def query-alive-str "ping")

(defn accept-request-and-respond [executor response raw-request]
  (try
    (let [request (json/read-json (.toString raw-request))]
      (when-not (= request query-alive-str)
        (executor request)
        (send current-petitions inc))
      (enqueue response (pr-str {:accepted true
                                 :current-petitions @current-petitions})))
    (catch Throwable e
      (log/error (str "Error processing: " raw-request) e)
      (enqueue response (pr-str {:accepted false
                                 :error (stacktrace/pst-str e)})))))

(defn wrap-execution-with [mongo-connection f]
  (fn [& args]
    (mongo/with-mongo mongo-connection
      (apply f args))))

(defn wrap-executions-with [mongo-connection]
  (partial wrap-execution-with mongo-connection))

(defn petition-handler [mongo-connection channel connection-info]
  (c/pipelined-server channel
                      (partial
                       (var accept-request-and-respond)
                       (partial accept-request (wrap-executions-with mongo-connection)))))

(defcodec protocol-frame
  (finite-frame :int32 (string :utf-8)))

(defn server [mongo-connection port]
  (let [result (tcp/start-tcp-server (partial petition-handler mongo-connection)
                                     {:frame protocol-frame
                                      :port port})]
    (log/info (str "Worker listening on " port))
    result))

(def server-id (.toString (UUID/randomUUID)))

(defn- guess-external-local-ip []
  (if-let [candidate (->> (NetworkInterface/getNetworkInterfaces)
                          (enumeration-seq)
                          (remove #(.isLoopback %))
                          (mapcat #(enumeration-seq (.getInetAddresses %)))
                          (filter #(.isSiteLocalAddress %))
                          (first))]
    (.getHostAddress candidate)))

(defn- add-connection-info [server conn]
  (with-meta server (-> (meta server) (merge {:conn conn}))))

(defn- get-connection-info [server]
  (:conn (meta server)))

(defmacro continue-on-error [& body]
  `(try
     ~@body
     (catch Throwable ~'e
       (log/error "Execution continues in spite of:" ~'e))))

(defn other-not-daemon-threads []
  (->> (Thread/getAllStackTraces)
       keys
       (filter #(not (.isDaemon %)))
       (remove (partial identical? (Thread/currentThread)))))

(defn exit-with-error-code [exit-code]
  (doseq [t (other-not-daemon-threads)]
    (.join t))
  (System/exit (- exit-code 256)))

(defn remove-registered-workers [server]
  (mongo/with-mongo (get-connection-info server)
    (mongo/destroy! :workers {:server-id server-id})))

(defn shutdown [server & {:keys [complete-exit] :or {complete-exit true}}]
  (continue-on-error
   (server))
  (continue-on-error
   (remove-registered-workers server))
  (continue-on-error
   (mongo/close-connection (get-connection-info server)))
  (when complete-exit
    (continue-on-error
     (.shutdownNow automator-executor)
     (shutdown-agents))))

(defn run [dbhost db-port db port ip-to-register-on threads-number]
  (defonce automator-executor (Executors/newFixedThreadPool (or threads-number 20)))
  (let [conn (mongo/make-connection db :host dbhost :port db-port)
        tcp-server (server conn port)
        tcp-server (add-connection-info tcp-server conn)]
    (try
      (mongo/with-mongo conn
        (mongo/add-index! :workers [:server-id])
        (mongo/set-write-concern conn :strict)
        (if-let [ip (or ip-to-register-on (guess-external-local-ip))]
          (mongo/update! :workers
                         {:host ip :port port}
                         {:$set {:server-id server-id}})))
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. #(remove-registered-workers tcp-server)))
      tcp-server
      (catch Throwable e
        (log/warn "Error connecting to mongo" e)
        (shutdown tcp-server)
        (exit-with-error-code 69))))) ;; EX_UNAVAILABLE

(defn parse-args [args]
  (cli args
       ["--mongo-host" "The hostname on which mongoDB is" :default "127.0.0.1"]
       ["--mongo-port" "The port on which the mongoDB to be used is listening to"
        :default 27017 :parse-fn #(Integer. %)]
       ["--mongo-db" "The name of the database to use within the mongoDB instance"
        :default "test"]
       ["-p" "--port" "The port this worker will listen to for requests" :default 40100 :parse-fn #(Integer. %)]
       ["--threads-number" :default 20 :parse-fn #(Integer. %)]
       ["--ip-to-run-on" "The ip on which to register the worker" :default nil]
       ["-h" "--help" "Print this help" :flag true :default false]))

(defn -main [& args]
  (let [[{:keys [mongo-host mongo-port mongo-db port threads-number ip-to-run-on help]} _ help-banner] (parse-args args)]
    (cond
     help (println help-banner)
     :else
     (run mongo-host mongo-port mongo-db port ip-to-run-on threads-number))))

(defn local-setup
  ([db port threads-number]
     (run "127.0.0.1" 27017 db port "127.0.0.1" threads-number))
  ([db port] (local-setup db port 10)))
