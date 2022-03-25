(ns metabase.models.permissions.parse
  "Parses sets of permissions to create a permission graph. Strategy is:

  - Convert strings to parse tree
  - Convert parse tree to path, e.g. ['3' :all] or ['3' :schemas :all]
  - Convert set of paths to a map, the permission graph"
  (:require [clojure.core.match :as match]
            [clojure.tools.logging :as log]
            [clojure.walk :as walk]
            [instaparse.core :as insta]
            [metabase.util.i18n :refer [trs]]))

(def ^:private grammar
  "Describes permission strings like /db/3/ or /collection/root/read/"
  "permission = ( all | db | download | collection | block )
  all         = <'/'>
  db          = <'/db/'> #'\\d+' <'/'> ( native | schemas )?
  native      = <'native/'>
  schemas     = <'schema/'> schema?
  schema      = #'[^/]*' <'/'> table?
  table       = <'table/'> #'\\d+' <'/'> (table-perm <'/'>)?
  table-perm  = ('read'|'query'|'query/segmented')

  download    = <'/download'> ( dl-limited | dl-db )
  dl-limited  = <'/limited'>  dl-db
  dl-db       = <'/db/'> #'\\d+' <'/'> ( dl-native | dl-schemas )?
  dl-native   = <'native/'>
  dl-schemas  = <'schema/'> dl-schema?
  dl-schema   = #'[^/]*' <'/'> dl-table?
  dl-table    = <'table/'> #'\\d+' <'/'>

  collection  = <'/collection/'> #'[^/]*' <'/'> ('read' <'/'>)?

  block       = <'/block/db/'> #'\\d+' <'/'>")

(def ^:private ^{:arglists '([s])} parser
  "Function that parses permission strings"
  (insta/parser grammar))

(defn- collection-id
  [id]
  (if (= id "root") :root (Long/parseUnsignedLong id)))

(defn- append-to-all
  "If `path-or-paths` is a single path, append `x` to the end of it. If it's a vector of paths, append `x` to each path."
  [path-or-paths x]
  (if (seqable? (first path-or-paths))
    (map (fn [path] (append-to-all path x)) (seq path-or-paths))
    (into path-or-paths [x])))

(defn- path
  "Recursively build permission path from parse tree"
  [tree]
  (match/match tree
    (_ :guard insta/failure?)      (log/error (trs "Error parsing permissions tree {0}" (pr-str tree)))
    [:permission t]                (path t)
    [:all]                         [:all] ; admin permissions
    [:db db-id]                    (let [db-id (Long/parseUnsignedLong db-id)]
                                     [[:db db-id :data :native :write]
                                      [:db db-id :data :schemas :all]])
    [:db db-id db-node]            (let [db-id (Long/parseUnsignedLong db-id)]
                                     (into [:db db-id] (path db-node)))
    [:schemas]                     [:data :schemas :all]
    [:schemas schema]              (into [:data :schemas] (path schema))
    [:schema schema-name]          [schema-name :all]
    [:schema schema-name table]    (into [schema-name] (path table))
    [:table table-id]              [(Long/parseUnsignedLong table-id) :all]
    [:table table-id table-perm]   (into [(Long/parseUnsignedLong table-id)] (path table-perm))
    [:table-perm perm]              (case perm
                                      "read"            [:read :all]
                                      "query"           [:query :all]
                                      "query/segmented" [:query :segmented])
    [:native]                      [:data :native :write]
    ;; download perms
    [:download
     [:dl-limited db-node]]        (append-to-all (path db-node) :limited)
    [:download db-node]            (append-to-all (path db-node) :full)
    [:dl-db db-id]                 (let [db-id (Long/parseUnsignedLong db-id)]
                                     #{[:db db-id :download :native]
                                       [:db db-id :download :schemas]})
    [:dl-db db-id db-node]         (let [db-id (Long/parseUnsignedLong db-id)]
                                     (into [:db db-id] (path db-node)))
    [:dl-schemas]                  [:download :schemas]
    [:dl-schemas schema]           (into [:download :schemas] (path schema))
    [:dl-schema schema-name]       [schema-name]
    [:dl-schema schema-name table] (into [schema-name] (path table))
    [:dl-table table-id]           [(Long/parseUnsignedLong table-id)]
    [:dl-native]                   [:download :native]
    ;; collection perms
    [:collection id]               [:collection (collection-id id) :write]
    [:collection id "read"]        [:collection (collection-id id) :read]
    ;; block perms. Parse something like /block/db/1/ to {:db {1 {:schemas :block}}}
    [:block db-id]                 [:db (Long/parseUnsignedLong db-id) :data :schemas :block]))

(defn- graph
  "Given a set of permission paths, return a graph that expresses the most permissions possible for the set

  Works by first doing a conversion like
  [[3 :schemas :all]
   [3 :schemas \"PUBLIC\" :all]
  ->
  {3 {:schemas {:all ()
                :public {:all ()}}}}

  Then converting that to
  {3 {:schemas :all}}"
  [paths]
  (->> paths
       (reduce (fn [paths path]
                 (if (every? vector? path) ;; handle case wher /db/x/ returns two vectors
                   (into paths path)
                   (conj paths path)))
               [])
       (walk/prewalk (fn [x]
                       (if (and (sequential? x)
                                (sequential? (first x))
                                (seq (first x)))
                         (->> x
                              (group-by first)
                              (reduce-kv (fn [m k v]
                                           (assoc m k (->> (map rest v)
                                                           (filter seq))))
                                         {}))
                         x)))
       (walk/prewalk (fn [x]
                       (or (when (map? x)
                             (some #(and (= (% x) '()) %)
                                   [:block :all :some :write :read :segmented :full :limited]))
                           x)))))

(defn permissions->graph
  "Given a set of permission strings, return a graph that expresses the most permissions possible for the set"
  [permissions]
  (->> permissions
       (map (comp path parser))
       graph))
