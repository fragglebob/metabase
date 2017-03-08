(ns metabase.query-processor.middleware.cache-test
  "Tests for the Query Processor cache."
  (:require [expectations :refer :all]
            [toucan.db :as db]
            [metabase.models.query-cache :refer [QueryCache]]
            [metabase.query-processor.middleware.cache :as cache]
            [metabase.test.util :as tu]))

(tu/resolve-private-vars metabase.query-processor.middleware.cache
  is-cacheable?
  secure-hash
  results-are-below-max-byte-threshold?)

(def ^:private mock-results
  {:row_count 8
   :status    :completed
   :data      {:rows [[:toucan      71]
                      [:bald-eagle  92]
                      [:hummingbird 11]
                      [:owl         10]
                      [:chicken     69]
                      [:robin       96]
                      [:osprey      72]
                      [:flamingo    70]]}})

(def ^:private mock-qp (constantly mock-results))

(def ^:private maybe-return-cached-results (cache/maybe-return-cached-results mock-qp))

(defn- clear-cache! [] (db/simple-delete! QueryCache))

(defn- cached? [results]
  (if (:cached? results)
    :cached
    :not-cached))

(defn- run-query [& {:as query-kvs}]
  (cached? (maybe-return-cached-results (merge {:cache-ttl 60, :query :abc} query-kvs))))


;;; ------------------------------------------------------------ Tests for secure-hash ------------------------------------------------------------

(defn- array= {:style/indent 0} [a b]
  (java.util.Arrays/equals a b))

;; secure-hash should always hash something the same way, every time
(expect
  (array=
    (byte-array [41, 6, -19, -29, -19, 124, -91, -26, -107, -120, -120, -32, -117, 102, -65, -122, -37, -38, 111, 19, -12, 100, -54, -119, 59, 86, -57, -96, 63, -57, -81, -96])
    (secure-hash {:query :abc})))

(expect
  (array= (secure-hash {:query :def})
          (secure-hash {:query :def})))

;; different queries should produce different hashes
(expect
  false
  (array=
    (secure-hash {:query :abc})
    (secure-hash {:query :def})))

(expect
  false
  (array=
    (secure-hash {:query :abc, :database 1})
    (secure-hash {:query :abc, :database 2})))

(expect
  false
  (array=
    (secure-hash {:query :abc, :type "query"})
    (secure-hash {:query :abc, :type "native"})))

(expect
  false
  (array=
    (secure-hash {:query :abc, :parameters [1]})
    (secure-hash {:query :abc, :parameters [2]})))

(expect
  false
  (array=
    (secure-hash {:query :abc, :constraints {:max-rows 1000}})
    (secure-hash {:query :abc, :constraints nil})))

;; ... but keys that are irrelevant to the query should be ignored by secure-hash
(expect
  (array=
    (secure-hash {:query :abc, :random :def})
    (secure-hash {:query :abc, :random :xyz})))


;;; ------------------------------------------------------------ tests for is-cacheable? ------------------------------------------------------------

;; something is-cacheable? if it includes a cache-ttl and the caching setting is enabled
(expect
  (tu/with-temporary-setting-values [enable-query-caching true]
    (is-cacheable? {:cache-ttl 100})))

(expect
  false
  (tu/with-temporary-setting-values [enable-query-caching false]
    (is-cacheable? {:cache-ttl 100})))

(expect
  false
  (tu/with-temporary-setting-values [enable-query-caching true]
    (is-cacheable? {:cache-ttl nil})))


;;; ------------------------------------------------------------ results-are-below-max-byte-threshold? ------------------------------------------------------------

(expect
  (tu/with-temporary-setting-values [query-caching-max-bytes 256]
    (results-are-below-max-byte-threshold? {:data {:rows [[1 "ABCDEF"]
                                                          [3 "GHIJKL"]]}})))

(expect
  false
  (tu/with-temporary-setting-values [query-caching-max-bytes 8]
    (results-are-below-max-byte-threshold? {:data {:rows [[1 "ABCDEF"]
                                                          [3 "GHIJKL"]]}})))

;; check that `results-are-below-max-byte-threshold?` is lazy and fails fast if the query is over the threshold rather than serializing the entire thing
(expect
  false
  (let [lazy-seq-realized? (atom false)]
    (tu/with-temporary-setting-values [query-caching-max-bytes 8]
      (results-are-below-max-byte-threshold? {:data {:rows (lazy-cat [[1 "ABCDEF"]
                                                                      [2 "GHIJKL"]
                                                                      [3 "MNOPQR"]]
                                                                     (do (reset! lazy-seq-realized? true)
                                                                         [[4 "STUVWX"]]))}})
      @lazy-seq-realized?)))


;;; ------------------------------------------------------------ End-to-end middleware tests ------------------------------------------------------------

;; if there's nothing in the cache, cached results should *not* be returned
(expect
  :not-cached
  (tu/with-temporary-setting-values [enable-query-caching true]
    (clear-cache!)
    (run-query)))

;; if we run the query twice, the second run should return cached results
(expect
  :cached
  (tu/with-temporary-setting-values [enable-query-caching true]
    (clear-cache!)
    (run-query)
    (run-query)))

;; ...but if the cache entry is past it's TTL, the cached results shouldn't be returned
(expect
  :not-cached
  (tu/with-temporary-setting-values [enable-query-caching true]
    (clear-cache!)
    (run-query :cache-ttl 1)
    (Thread/sleep 2000)
    (run-query :cache-ttl 1)))

;; if caching is disabled then cache shouldn't be used even if there's something valid in there
(expect
  :not-cached
  (tu/with-temporary-setting-values [enable-query-caching true]
    (clear-cache!)
    (run-query)
    (tu/with-temporary-setting-values [enable-query-caching false]
      (run-query))))


;; check that `query-caching-max-bytes` is respected and queries aren't cached if they're past the threshold
(expect
  :not-cached
  (tu/with-temporary-setting-values [enable-query-caching    true
                                     query-caching-max-bytes 8]
    (clear-cache!)
    (run-query)
    (run-query)))

;; check that `query-caching-max-ttl` is respected. Whenever a new query is cached the cache should evict any entries older that `query-caching-max-ttl`.
;; Set max-ttl to one second, run query `:abc`, then wait two seconds, and run `:def`. This should trigger the cache flush for entries past `:max-ttl`;
;; and the cached entry for `:abc` should be deleted. Running `:abc` a subsequent time should not return cached results
(expect
  :not-cached
  (tu/with-temporary-setting-values [enable-query-caching  true
                                     query-caching-max-ttl 1]
    (clear-cache!)
    (run-query)
    (Thread/sleep 2000)
    (run-query, :query :def)
    (run-query)))

;; check that *ignore-cached-results* is respected when returning results...
(expect
  :not-cached
  (tu/with-temporary-setting-values [enable-query-caching  true]
    (clear-cache!)
    (run-query)
    (binding [cache/*ignore-cached-results* true]
      (run-query))))

;; ...but if it's set those results should still be cached for next time.
(expect
  :cached
  (tu/with-temporary-setting-values [enable-query-caching  true]
    (clear-cache!)
    (binding [cache/*ignore-cached-results* true]
      (run-query))
    (run-query)))
