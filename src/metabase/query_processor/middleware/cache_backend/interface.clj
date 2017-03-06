(ns metabase.query-processor.middleware.cache-backend.interface
  "Interface used to define different Query Processor cache backends.
   Defining a backend is straightforward: define a new namespace with the pattern

     metabase.query-processor.middleware.cache-backend.<backend>

   Where backend is a key representing the backend, e.g. `db`, `redis`, or `memcached`.

   In that namespace, create an object that reifies (or otherwise implements) `IQueryProcessorCacheBackend`.
   This object *must* be stored in a var called `instance`.

   That's it. See `metabase.query-processor.middleware.cache-backend.db` for a complete example of how this is done.")

(def ^:const ^Integer max-cache-entry-age-seconds
  "The maximum age any entry should be kept in the cache.
   Entries older than this age can be purged."
  (* 60 60 24 100)) ; one hundred days

(defprotocol IQueryProcessorCacheBackend
  "Protocol that different Metabase cache backends must implement."
  (cached-results [this, ^Integer query-hash, ^Integer max-age-seconds]
    "Return cached results for the query with QUERY-HASH if those results are present in the cache and are less
     than MAX-AGE-SECONDS old. Otherwise, return `nil`.")

  (save-results! [this, ^Integer query-hash, results]
    "Add a cache entry with the RESULTS of running query with QUERY-HASH.
     This should replace any prior entries for QUERY-HASH and update the cache timestamp to the current system time.
     (This is also an appropriate point to purge any entries older than `max-cache-entry-age-seconds`.)"))
