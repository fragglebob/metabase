(ns metabase.query-processor.middleware.cache-backend.db
  (:require [toucan.db :as db]
            (metabase.models [interface :as models]
                             [query-cache :refer [QueryCache]])
            [metabase.public-settings :as public-settings]
            [metabase.query-processor.middleware.cache-backend.interface :as i]
            [metabase.util :as u]))

(defn- cached-results
  "Return cached results for QUERY-HASH if they exist and are newer than MAX-AGE-SECONDS."
  [query-hash max-age-seconds]
  (db/select-one-field :results QueryCache
    :query_hash query-hash
    :updated_at [:>= (u/->Timestamp (- (System/currentTimeMillis)
                                       (* 1000 max-age-seconds)))]))

(defn- purge-old-cache-entries!
  "Delete any cache entries that are older than the global max age `max-cache-entry-age-seconds` (currently 3 months)."
  []
  (db/delete! QueryCache
    :updated_at [:<= (u/->Timestamp (- (System/currentTimeMillis)
                                       (* 1000 (public-settings/query-caching-max-ttl))))]))

(defn- save-results!
  "Save the RESULTS of query with QUERY-HASH, updating an existing QueryCache entry
  if one already exists, otherwise creating a new entry."
  [query-hash results]
  (purge-old-cache-entries!)
  (or (db/update-where! QueryCache {:query_hash query-hash}
        :updated_at (u/new-sql-timestamp)
        :results    (models/compress results)) ; have to manually call these here since Toucan doesn't call type conversion fns for update-where! (yet)
      (db/insert! QueryCache
        :query_hash query-hash
        :results    results))
  :ok)

(def ^:private instance
  "Implementation of `IQueryProcessorCacheBackend` that uses the database for caching results."
  (reify i/IQueryProcessorCacheBackend
    (cached-results [_ query-hash max-age-seconds] (cached-results query-hash max-age-seconds))
    (save-results!  [_ query-hash results]         (save-results! query-hash results))))
