(ns metabase.query-processor.middleware.cache
  "Middleware that returns cached results for queries when applicable.
   Cache TTLs are set on a per-card basis; if a Card has a cache TTL set then cache results will be returned if possible.
   For all other queries, caching is skipped.

   Various caching backends are defined in `metabase.query-processor.middleware.cache-backend` namespaces.
   The default backend is `db`, which uses the application database; this value can be changed by setting the env var
   `MB_QP_CACHE_BACKEND`.

    Refer to `metabase.query-processor.middleware.cache-backend.interface` for more details about how the cache backends themselves."
  (:require [clojure.tools.logging :as log]
            [metabase.config :as config]
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

(defn- run-query-with-cache [qp {{query-hash :query-hash} :info, cache-ttl :cache_ttl, :as query}]
  (or (u/prog1 (cached-results query-hash cache-ttl)
        (when <>
          (log/info "Returning cached results for query with hash:" (u/format-color 'green query-hash) (u/emoji "💰"))))
      ;; if query is not in the cache, run it, and save the results *if* it completes successfully
      (u/prog1 (qp query)
        (when (= (:status <>) :completed)
          (log/info "Caching results for next time for query with hash:" (u/format-color 'green query-hash) (u/emoji "💰"))
          (save-results! query-hash <>)))))

(defn- is-cacheable? ^Boolean [{{query-hash :query-hash} :info, cache-ttl :cache_ttl}]
  (boolean (and query-hash cache-ttl)))

(defn maybe-return-cached-results [qp]
  (fn [query]
    (if-not (is-cacheable? query)
      (qp query)
      (run-query-with-cache qp query))))
