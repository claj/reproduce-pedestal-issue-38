(ns repro.issue38
  "reproduces the error described in
  https://github.com/pedestal/pedestal/issues/629"
  (:require [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.body-params :as body-params]
            [io.pedestal.http.route.definition :refer [defroutes]]
            [clojure.string :refer [trim-newline]]
            [clojure.java.shell :refer [sh]])
  (:import (org.eclipse.jetty.util.ssl SslContextFactory)
           (org.eclipse.jetty.http2 HTTP2Cipher)
           (org.eclipse.jetty.server Server ServerConnector)
           (org.eclipse.jetty.servlet ServletContextHandler)
           (java.security KeyStore)))

(defn hello-world
  [request]
  (let [name (get-in request [:params :name] "World")]
    {:status 200 :body (str "Hello " name "!\n" )}))

(defroutes routes
  [[["/"
     ["/hello" {:get hello-world}]]]])

(defn read-password [path]
  (trim-newline (slurp path)))

(def ^:dynamic *SETTING* "repro_default")
(def ^:dynamic *PORT* 4443)
(def ^:dynamic *service-factory* )

;; store server in atom to be able to shut it down
(defonce current-server (atom nil))

(defn maybe-stop-server
  "stops server in atom"
  []
  (if-let [stop-fn (:io.pedestal.http/stop-fn (:server @current-server))]
    (do (println "stoppping server")
        (stop-fn))))

(defn generate-pki
  "generates a new pki, forcefully overwrites current config with same name"
  []
  (println "generate pki for" *SETTING*)
  (sh "bash" "generate-pki.sh" *SETTING*))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Working setup, using an SslContextFactory
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- ^SslContextFactory ssl-context-factory
  "Creates a new SslContextFactory instance from a map of options."
  [options]
  (let [{:keys [^KeyStore keystore
                ^String key-password
                ^KeyStore truststore
                ^String trust-password
                ^String security-provider
                client-auth]} options
        ^SslContextFactory context (SslContextFactory.)]
    (when (every? nil? [keystore key-password truststore trust-password client-auth])
      (throw (IllegalArgumentException. "You are attempting to use SSL, but you did not supply any certificate management (KeyStore/TrustStore/etc.)")))
    (if (string? keystore)
      (.setKeyStorePath context keystore)
      (.setKeyStore context keystore))
    (.setKeyStorePassword context key-password)
    (when truststore
      (if (string? truststore)
        (.setTrustStorePath context truststore)
        (.setTrustStore context truststore)))
    (when trust-password
      (.setTrustStorePassword context trust-password))
    (when security-provider
      (.setProvider context security-provider))
    (case client-auth
      :need (.setNeedClientAuth context true)
      :want (.setWantClientAuth context true)
      nil)
    (.setCipherComparator context HTTP2Cipher/COMPARATOR)
    (.setUseCipherSuitesOrder context true)
    (.setEndpointIdentificationAlgorithm context nil)
    context))

(defn WORKING-create-service []
  (let [context-factory-options
        {:keystore (str "config/" *SETTING* "/jetty-keystore.p12")
         :key-password (read-password (str "config/" *SETTING* "/jetty-keystore.password"))
         :truststore (str "config/" *SETTING* "/jetty-trust.p12")
         :trust-password (read-password (str "config/" *SETTING* "/jetty-trust.password"))
         :client-auth :need}
        service {:env                 :prod
                 ::http/routes        routes
                 ::http/resource-path "/public"
                 ::http/type          :jetty
                 ;;::http/port          8080
                 ::http/container-options {:h2c? false
                                           :h2? false
                                           :ssl? true
                                           :ssl-port *PORT*
                                           :ssl-context-factory (ssl-context-factory context-factory-options)}}]
    service))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; broken setup, using Pedestal default contruction
;; for client certificates
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn BROKEN-create-service-without-ssl-context-factory []
  {:env                 :prod
   ::http/routes        routes
   ::http/resource-path "/public"
   ::http/type          :jetty
   ;;::http/port          8080
   ::http/container-options {:h2c? false
                             :h2? false
                             :ssl? true
                             :ssl-port *PORT*
                             :keystore (str "config/" *SETTING* "/jetty-keystore.p12")
                             :key-password (read-password (str "config/" *SETTING* "/jetty-keystore.password"))
                             :truststore (str "config/" *SETTING* "/jetty-trust.p12")
                             :trust-password (read-password (str "config/" *SETTING* "/jetty-trust.password"))
                             :client-auth :need}})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; end of broken setup
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn start-server []
  (maybe-stop-server)
  (let [service (*service-factory*)
        starting-server
        (-> service ;; start with production configuration
            (merge {:env :dev
                    ;; do not block thread that starts web server
                    ::http/join? false
                    ::http/routes #(deref #'routes)
                    ;; all origins are allowed in dev mode
                    ::http/allowed-origins {:creds true :allowed-origins (constantly true)}})
            ;; Wire up interceptor chains
            http/default-interceptors
            http/dev-interceptors
            http/create-server
            http/start)]
    (println "starting server")
    (reset! current-server {:setting *SETTING*
                            :port *PORT*
                            :server starting-server})))

(defn call-with-curl []
  (let [commands ["curl" "-v"
                  "--cacert" (str "config/" *SETTING* "/" *SETTING* "_root_ca.crt")
                  "--cert" (str "config/" *SETTING* "/clientcert.crt")
                  "--key" (str "config/" *SETTING* "/clientcert.key")

                  "--http1.1"
                  "--silent"
                  (str "https://repro.local:" *PORT* "/hello")]

        res (apply sh commands)]
    (if-not (zero? (:exit res))
      (do (println "ERROR: something wrong in the call with curl")
          (println (clojure.string/join " " commands))
          (prn res)))

    (println "SUCCESS: success calling server on " *SETTING*)
    res))

(comment
  "complete WORKING test case, see *out* for results"


  (with-bindings {#'*SETTING* "repro_working"
                  #'*service-factory* WORKING-create-service}
    (println "WORKING case:")
    (generate-pki)
    (start-server)
    (call-with-curl)
    (maybe-stop-server))

  )

(comment

  "complete BROKEN test case, see *out* for results"
  (with-bindings {#'*SETTING* "repro_broken"
                  #'*service-factory* BROKEN-create-service-without-ssl-context-factory}
    (println "BROKEN case:")
    (generate-pki)
    (start-server)
    (call-with-curl)
    (maybe-stop-server))

  )
