(ns metabase.query-processor.middleware.cache
  "Middleware that returns cached results for queries when applicable.

   If caching is enabled (`enable-query-caching` is `true`) cached results will be returned for Cards if possible. There's
   a global default TTL defined by the setting `query-caching-default-ttl`, but individual Cards can override this value
   with custom TTLs with a value for `:cache_ttl`.

   For all other queries, caching is skipped.

   Various caching backends are defined in `metabase.query-processor.middleware.cache-backend` namespaces.
   The default backend is `db`, which uses the application database; this value can be changed by setting the env var
   `MB_QP_CACHE_BACKEND`.

    Refer to `metabase.query-processor.middleware.cache-backend.interface` for more details about how the cache backends themselves."
  (:require [clojure.tools.logging :as log]
            [buddy.core.hash :as hash]
            [metabase.config :as config]
            [metabase.public-settings :as public-settings]
            [metabase.query-processor.middleware.cache-backend.interface :as i]
            [metabase.util :as u]))

;;; ------------------------------------------------------------ Backend ------------------------------------------------------------

(def ^:private backend-instance
  (atom nil))

(defn- set-backend!
  "Set the cache backend to the cache defined by the keyword BACKEND.

   (This should be something like `:db`, `:redis`, or `:memcached`. See the
   documentation in `metabase.query-processor.middleware.cache-backend.interface` for details on how this works.)"
  [backend]
  (let [backend-ns (symbol (str "metabase.query-processor.middleware.cache-backend." (munge (name backend))))]
    (require backend-ns)
    (log/info "Using query processor cache backend:" (u/format-color 'blue backend) (u/emoji "💰"))
    (let [instance (ns-resolve backend-ns 'instance)]
      (assert instance
        (str "No var named 'instance' found in namespace " backend-ns))
      (assert (extends? i/IQueryProcessorCacheBackend (class @instance))
        (str "%s/instance doesn't satisfy IQueryProcessorCacheBackend" backend-ns))
      (reset! backend-instance @instance))))

(set-backend! (config/config-str :mb-qp-cache-backend))


;;; ------------------------------------------------------------ Cache Operations ------------------------------------------------------------

(defn- cached-results [query-hash max-age-seconds] (i/cached-results @backend-instance query-hash max-age-seconds))
(defn- save-results!  [query-hash results]         (i/save-results!  @backend-instance query-hash results))


;;; ------------------------------------------------------------ Middleware ------------------------------------------------------------

(defn- secure-hash
  "Return a 512-bit SHA3 hash of QUERY as a key for the cache. (This is returned as a byte array.)"
  [query]
  (hash/sha3-512 (str (select-keys query [:database :type :query :parameters]))))

(defn- run-query-with-cache [qp {cache-ttl :cache_ttl, :as query}]
  (let [query-hash (secure-hash query)]
    (or (u/prog1 (cached-results query-hash cache-ttl)
          (when <>
            (log/info "Returning cached results for query" (u/emoji "💰"))))
        ;; if query is not in the cache, run it, and save the results *if* it completes successfully
        (u/prog1 (qp query)
          (when (and (= (:status <>) :completed)
                     (<= (:row_count <>) (public-settings/query-caching-max-rows)))
            (log/info "Caching results for next time for query" (u/emoji "💰"))
            (save-results! query-hash <>))))))

(defn- is-cacheable? ^Boolean [{cache-ttl :cache_ttl}]
  (boolean (and (public-settings/enable-query-caching)
                cache-ttl)))

(defn maybe-return-cached-results [qp]
  (fn [query]
    (if-not (is-cacheable? query)
      (qp query)
      (run-query-with-cache qp query))))
