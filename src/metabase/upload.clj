(ns metabase.upload
  (:refer-clojure :exclude [derive make-hierarchy parents])
  (:require
   [clj-bom.core :as bom]
   [clojure.data :as data]
   [clojure.data.csv :as csv]
   [clojure.string :as str]
   [flatland.ordered.map :as ordered-map]
   [java-time.api :as t]
   [medley.core :as m]
   [metabase.analytics.snowplow :as snowplow]
   [metabase.api.common :as api]
   [metabase.driver :as driver]
   [metabase.driver.sync :as driver.s]
   [metabase.driver.util :as driver.u]
   [metabase.events :as events]
   [metabase.lib.core :as lib]
   [metabase.mbql.util :as mbql.u]
   [metabase.models :refer [Database]]
   [metabase.models.card :as card]
   [metabase.models.collection :as collection]
   [metabase.models.data-permissions :as data-perms]
   [metabase.models.humanization :as humanization]
   [metabase.models.interface :as mi]
   [metabase.models.table :as table]
   [metabase.public-settings :as public-settings]
   [metabase.public-settings.premium-features :as premium-features]
   [metabase.sync :as sync]
   [metabase.sync.sync-metadata.fields :as sync-fields]
   [metabase.sync.sync-metadata.tables :as sync-tables]
   [metabase.upload.parsing :as upload-parsing]
   [metabase.util :as u]
   [metabase.util.i18n :refer [tru]]
   [metabase.util.malli :as mu]
   [metabase.util.malli.schema :as ms]
   [metabase.util.ordered-hierarchy :as ordered-hierarchy :refer [derive make-hierarchy]]
   [toucan2.core :as t2])
  (:import
   (java.io File)))

(set! *warn-on-reflection* true)

;;;; <pre><code>
;;;;
;;;; +------------------+
;;;; | Schema detection |
;;;; +------------------+

;; Upload types form a DAG (directed acyclic graph) where each type can be coerced into any
;; of its ancestors types. We parse each value in the CSV file to the most-specific possible
;; type for each column. The most-specific possible type for a column is the lowest common
;; ancestor of the types for each value in the column.
;;
;;              text
;;               |
;;               |
;;          varchar-255┐
;;        /     / \    │
;;       /     /   \   └──────────┬
;;      /     /     \             │
;;  boolean float   datetime  offset-datetime
;;     |     │       │
;;     │     │       │
;;     |    int     date
;;     |   /   \
;;     |  /     \
;;     | /       \
;;     |/         \
;; boolean-or-int  auto-incrementing-int-pk
;;
;; `boolean-or-int` is a special type with two parents, where we parse it as a boolean if the whole
;; column's values are of that type. additionally a column cannot have a boolean-or-int type, but
;; a value can. if there is a column with a boolean-or-int value and an integer value, the column will be int
;; if there is a column with a boolean-or-int value and a boolean value, the column will be boolean
;; if there is a column with only boolean-or-int values, the column will be parsed as if it were boolean
;;
;; </code></pre>

(def ^:private h
  "This hierarchy defines a relationship between value types and their specializations.
  We use an [[metabase.util.ordered-hierarchy]] for its topological sorting, which simplify writing efficient and
  consistent implementations for of our type inference, parsing, and relaxation."
  (-> (make-hierarchy)
      (derive ::boolean-or-int ::boolean)
      (derive ::boolean-or-int ::int)
      (derive ::auto-incrementing-int-pk ::int)
      (derive ::int ::float)
      (derive ::date ::datetime)
      (derive ::boolean ::varchar-255)
      (derive ::offset-datetime ::varchar-255)
      (derive ::datetime ::varchar-255)
      (derive ::float ::varchar-255)
      (derive ::varchar-255 ::text)))

(def ^:private abstract->concrete
  "Not all value types correspond to database types. For those that don't, this maps to their concrete ancestor."
  {::boolean-or-int ::boolean})

(def ^:private value-types
  "All type tags which values can be inferred as. An ordered set from most to least specialized."
  (ordered-hierarchy/sorted-tags h))

(def ^:private column-types
  "All type tags that correspond to concrete column types."
  (into #{} (remove abstract->concrete) value-types))

(defn ^:private column-type
  "The most specific column type corresponding to the given value type."
  [value-type]
  (or (abstract->concrete value-type value-type)
      ;; If we know nothing about the value type, treat it as an arbitrary string.
      ::text))

;;;;;;;;;;;;;;;;;;;;;;;;;;
;; [[value->type]] helpers

(defn- with-parens
  "Returns a regex that matches the argument, with or without surrounding parentheses."
  [number-regex]
  (re-pattern (str "(" number-regex ")|(\\(" number-regex "\\))")))

(defn- with-currency
  "Returns a regex that matches a positive or negative number, including currency symbols"
  [number-regex]
  ;; currency signs can be all over: $2, -$2, $-2, 2€
  (re-pattern (str upload-parsing/currency-regex "?\\s*-?"
                   upload-parsing/currency-regex "?"
                   number-regex
                   "\\s*" upload-parsing/currency-regex "?")))

(defn- int-regex [number-separators]
  (with-parens
    (with-currency
      (case number-separators
        ("." ".,") #"\d[\d,]*"
        ",." #"\d[\d.]*"
        ", " #"\d[\d \u00A0]*"
        ".’" #"\d[\d’]*"))))

(defn- float-regex [number-separators]
  (with-parens
    (with-currency
      (case number-separators
        ("." ".,") #"\d[\d,]*\.\d+"
        ",." #"\d[\d.]*\,[\d]+"
        ", " #"\d[\d \u00A0]*\,[\d.]+"
        ".’" #"\d[\d’]*\.[\d.]+"))))

(defmacro does-not-throw?
  "Returns true if the given body does not throw an exception."
  [body]
  `(try
     ~body
     true
     (catch Throwable e#
       false)))

(defn- date-string? [s]
  (does-not-throw? (upload-parsing/parse-local-date s)))

(defn- datetime-string? [s]
  (does-not-throw? (upload-parsing/parse-local-datetime s)))

(defn- offset-datetime-string? [s]
  (does-not-throw? (upload-parsing/parse-offset-datetime s)))

(defn- boolean-string? [s]
  (boolean (re-matches #"(?i)true|t|yes|y|1|false|f|no|n|0" s)))

(defn- boolean-or-int-string? [s]
  (contains? #{"0" "1"} s))

(defn- varchar-255? [s]
  (<= (count s) 255))

(defn- regex-matcher [regex]
  (fn [s]
    (boolean (re-matches regex s))))

;; end [[value->type]] helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private non-inferable-types
  #{::auto-incrementing-int-pk})

(def ^:private type->check-schema
  "Every inferable value-type needs to have a detection function registered."
  (into [:map] (map #(vector % [:=> [:cat :string] :boolean])
                    (remove non-inferable-types value-types))))

(mu/defn ^:private settings->type->check :- type->check-schema
  [{:keys [number-separators] :as _settings}]
  {::boolean-or-int  boolean-or-int-string?
   ::boolean         boolean-string?
   ::offset-datetime offset-datetime-string?
   ::date            date-string?
   ::datetime        datetime-string?
   ::int             (regex-matcher (int-regex number-separators))
   ::float           (regex-matcher (float-regex number-separators))
   ::varchar-255     varchar-255?
   ::text            (constantly true)})

(defn- value->type
  "Determine the most specific type that is compatible with the given value.
  Numbers are assumed to use separators corresponding to the locale defined in the application settings"
  [type->check value]
  (when-not (str/blank? value)
    (let [trimmed (str/trim value)]
      (->> (remove non-inferable-types value-types)
           (filter #((type->check %) trimmed))
           first))))

(defn- relax-type
  "Given an existing column type, and a new value, relax the type until it includes the value."
  [type->check current-type value]
  (cond (nil? value) current-type
        (nil? current-type) (value->type type->check value)
        :else (let [trimmed (str/trim value)]
                (if (str/blank? trimmed)
                  current-type
                  (->> (cons current-type (ancestors h current-type))
                       (filter #((type->check %) trimmed))
                       first)))))

(defn- normalize-column-name
  [raw-name]
  (if (str/blank? raw-name)
    "unnamed_column"
    (u/slugify (str/trim raw-name))))

(def auto-pk-column-name
  "The lower-case name of the auto-incrementing PK column. The actual name in the database could be in upper-case."
  "_mb_row_id")

(def auto-pk-column-keyword
  "The keyword of the auto-incrementing PK column."
  (keyword auto-pk-column-name))

(defn- table-id->auto-pk-column [table-id]
  (first (filter (fn [field]
                   (= (normalize-column-name (:name field)) auto-pk-column-name))
                 (t2/select :model/Field :table_id table-id :active true))))

(defn- type-relaxer
  "Given a map of {value-type -> predicate}, return a reducing fn which updates our inferred schema using the next row."
  [settings]
  (let [relax (partial relax-type (settings->type->check settings))]
    (fn [value-types row]
      ;; It's important to realize this lazy sequence, because otherwise we can build a huge stack and overflow.
      (vec (u/map-all relax value-types row)))))

(mu/defn column-types-from-rows :- [:sequential (into [:enum] column-types)]
  "Given the types of the existing columns (if there are any), and rows to be added, infer the best supporting types."
  [settings existing-types rows]
  (map column-type (reduce (type-relaxer settings) existing-types rows)))

(defn- detect-schema
  "Consumes the header and rows from a CSV file.

   Returns a map with two keys:
     - `:extant-columns`: an ordered map of columns found in the CSV file, excluding columns that have the same normalized name as the generated columns.
     - `:generated-columns`: an ordered map of columns we are generating ourselves. Currently, this is just the auto-incrementing PK.

   The value of `extant-columns` and `generated-columns` is an ordered map of normalized-column-name -> type for the
   given CSV file. Supported types include `::int`, `::datetime`, etc. A column that is completely blank is assumed to
   be of type `::text`."
  [settings header rows]
  (let [normalized-header   (map normalize-column-name header)
        unique-header       (map keyword (mbql.u/uniquify-names normalized-header))
        column-count        (count normalized-header)
        initial-types       (repeat column-count nil)
        col-name+type-pairs (->> (column-types-from-rows settings initial-types rows)
                                 (map vector unique-header))]
    {:extant-columns    (ordered-map/ordered-map col-name+type-pairs)
     :generated-columns (ordered-map/ordered-map auto-pk-column-keyword ::auto-incrementing-int-pk)}))


;;;; +------------------+
;;;; |  Parsing values  |
;;;; +------------------+

(def ^:private last-timestamp (atom (t/local-date-time)))

(set! *warn-on-reflection* true)

(defn strictly-monotonic-now
  "Return an adjusted version of the current time, that it is guaranteed to never repeat the last second."
  []
  (swap! last-timestamp
         (fn [prev-timestamp]
           (t/max
             (t/local-date-time)
             (-> prev-timestamp
                 (t/plus (t/seconds 1))
                 (t/truncate-to :seconds))))))

(defn- unique-table-name
  "Append the current datetime to the given name to create a unique table name. The resulting name will be short enough for the given driver (truncating the supplied `table-name` if necessary)."
  [driver table-name]
  (let [time-format                 "_yyyyMMddHHmmss"
        slugified-name               (or (u/slugify table-name) "")
        max-length                  (- (driver/table-name-length-limit driver) (count time-format))
        acceptable-length           (min (count slugified-name) max-length)
        truncated-name-without-time (subs slugified-name 0 acceptable-length)]
    (str truncated-name-without-time
         (t/format time-format (strictly-monotonic-now)))))

(defn- column-definitions
  "Returns a map of column-name -> column-definition from a map of column-name -> upload-type."
  [driver col->upload-type]
  (update-vals col->upload-type (partial driver/upload-type->database-type driver)))

(defn current-database
  "The database being used for uploads (as per the `uploads-database-id` setting)."
  []
  (t2/select-one Database :id (public-settings/uploads-database-id)))

(mu/defn ^:private table-identifier
  "Returns a string that can be used as a table identifier in SQL, including a schema if provided."
  [{:keys [schema name] :as _table}
   :- [:map
       [:schema {:optional true} [:maybe :string]]
       [:name :string]]]
  (if (str/blank? schema)
    name
    (str schema "." name)))

(defn- parse-rows
  "Returns a lazy seq of parsed rows, given a sequence of upload types for each column.
  Empty strings are parsed as nil."
  [settings col-upload-types rows]
  (let [parsers (map #(upload-parsing/upload-type->parser % settings) col-upload-types)]
    (for [row rows]
      (for [[value parser] (u/map-all vector row parsers)]
        (when-not (str/blank? value)
          (parser value))))))

(defn- remove-indices
  "Removes the elements at the given indices from the collection. Indices is a set."
  [indices coll]
  (keep-indexed (fn [idx item]
                  (when-not (contains? indices idx)
                    item))
                coll))

(defn- indices-where
  "Returns a lazy seq of the indices where the predicate is true."
  [pred coll]
  (keep-indexed (fn [idx item]
                  (when (pred item)
                    idx))
                coll))

(defn- auto-pk-column-indices
  "Returns the indices of columns that have the same normalized name as [[auto-pk-column-name]]"
  [header]
  (set (indices-where #(= auto-pk-column-name (normalize-column-name %)) header)))

(defn- without-auto-pk-columns
  [header-and-rows]
  (let [header (first header-and-rows)
        auto-pk-indices (auto-pk-column-indices header)]
    (cond->> header-and-rows
      auto-pk-indices
      (map (partial remove-indices auto-pk-indices)))))

(defn- file-size-mb [csv-file]
  (/ (.length ^File csv-file) 1048576.0))

(defn- load-from-csv!
  "Loads a table from a CSV file. If the table already exists, it will throw an error.
   Returns the file size, number of rows, and number of columns."
  [driver db-id table-name ^File csv-file]
  (with-open [reader (bom/bom-reader csv-file)]
    (let [[header & rows]         (without-auto-pk-columns (csv/read-csv reader))
          settings                (upload-parsing/get-settings)
          {:keys [extant-columns generated-columns]} (detect-schema settings header rows)
          cols->upload-type       (merge generated-columns extant-columns)
          col-definitions         (column-definitions driver cols->upload-type)
          csv-col-names           (keys extant-columns)
          col-upload-types        (vals extant-columns)
          parsed-rows             (vec (parse-rows settings col-upload-types rows))]
      (driver/create-table! driver
                            db-id
                            table-name
                            col-definitions
                            :primary-key [auto-pk-column-keyword])
      (try
        (driver/insert-into! driver db-id table-name csv-col-names parsed-rows)
        {:num-rows          (count rows)
         :num-columns       (count extant-columns)
         :generated-columns (count generated-columns)
         :size-mb           (file-size-mb csv-file)}
        (catch Throwable e
          (driver/drop-table! driver db-id table-name)
          (throw (ex-info (ex-message e) {:status-code 400})))))))

;;;; +------------------+
;;;; |  Create upload
;;;; +------------------+

(def ^:dynamic *sync-synchronously?*
  "For testing purposes, often we'd like to sync synchronously so that we can test the results immediately and avoid
  race conditions."
  false)

(defn- scan-and-sync-table!
  [database table]
  (sync-fields/sync-fields-for-table! database table)
  (if *sync-synchronously?*
    (sync/sync-table! table)
    (future
      (sync/sync-table! table))))

(defn- can-use-uploads-error
  "Returns an ExceptionInfo object if the user cannot upload to the given database for the subset of reasons common to all uploads
  entry points. Returns nil otherwise."
  [db]
  (let [driver (driver.u/database->driver db)]
    (cond
      (not (public-settings/uploads-enabled))
      (ex-info (tru "Uploads are not enabled.")
               {:status-code 422})

      (premium-features/sandboxed-user?)
      (ex-info (tru "Uploads are not permitted for sandboxed users.")
               {:status-code 403})

      (not (driver/database-supports? driver :uploads nil))
      (ex-info (tru "Uploads are not supported on {0} databases." (str/capitalize (name driver)))
               {:status-code 422}))))

(defn- can-create-upload-error
  "Returns an ExceptionInfo object if the user cannot upload to the given database and schema. Returns nil otherwise."
  [db schema-name]
  (or (can-use-uploads-error db)
      (cond
        (and (str/blank? schema-name)
             (driver/database-supports? (driver.u/database->driver db) :schemas db))
        (ex-info (tru "A schema has not been set.")
                 {:status-code 422})
        (not= :unrestricted (data-perms/full-schema-permission-for-user api/*current-user-id*
                                                                        :perms/data-access
                                                                        (u/the-id db)
                                                                        schema-name))
        (ex-info (tru "You don''t have permissions to do that.")
                 {:status-code 403})
        (and (some? schema-name)
             (not (driver.s/include-schema? db schema-name)))
        (ex-info (tru "The schema {0} is not syncable." schema-name)
                 {:status-code 422}))))

(defn- check-can-create-upload
  "Throws an error if the user cannot upload to the given database and schema."
  [db schema-name]
  (when-let [error (can-create-upload-error db schema-name)]
    (throw error)))

(defn can-create-upload?
  "Returns true if the user can upload to the given database and schema, and false otherwise."
  [db schema-name]
  (nil? (can-create-upload-error db schema-name)))

(defn- start-timer [] (System/nanoTime))

(defn- since-ms [timer] (/ (- (System/nanoTime) timer) 1e6))

;;; +-----------------------------------------
;;; |  public interface for creating CSV table
;;; +-----------------------------------------

(defn- fail-stats
  "If a given upload / append / replace fails, this function is used to create the failure event payload for snowplow.
  It may involve redundantly reading the file, or even failing again if the file is unreadable."
  [file]
  (with-open [reader (bom/bom-reader file)]
    (let [rows (csv/read-csv reader)]
      {:size-mb     (file-size-mb file)
       :num-columns (count (first rows))
       :num-rows    (count (rest rows))})))

(mu/defn create-csv-upload!
  "Main entry point for CSV uploading.

  What it does:
  - throws an error if the user cannot upload to the given database and schema (see [[can-create-upload-error]] for reasons)
  - throws an error if the user has write permissions to the given collection
  - detects the schema of the CSV file
  - inserts the data into a new table with a unique name, along with an extra auto-generated primary key column
  - syncs and scans the table
  - creates a model which wraps the table

  Requires that current-user dynamic vars in [[metabase.api.common]] are bound as if by API middleware (this is
  needed for QP permissions checks).
  Returns the newly created model. May throw validation, permimissions, or DB errors.

  Args:
  - `collection-id`: the ID of the collection to create the model in. `nil` means the root collection.
  - `filename`: the name of the file being uploaded.
  - `file`: the file being uploaded.
  - `db-id`: the ID of the database to upload to.
  - `schema-name`: the name of the schema to create the table in (optional).
  - `table-prefix`: the prefix to use for the table name (optional)."
  [{:keys [collection-id filename ^File file db-id schema-name table-prefix]}
   :- [:map
       [:collection-id [:maybe ms/PositiveInt]]
       [:filename :string]
       [:file (ms/InstanceOfClass File)]
       [:db-id ms/PositiveInt]
       [:schema-name {:optional true} [:maybe :string]]
       [:table-prefix {:optional true} [:maybe :string]]]]
  (let [database (or (t2/select-one Database :id db-id)
                     (throw (ex-info (tru "The uploads database does not exist.")
                                     {:status-code 422})))]
    (check-can-create-upload database schema-name)
    (collection/check-write-perms-for-collection collection-id)
    (try
      (let [timer             (start-timer)
            driver            (driver.u/database->driver database)
            filename-prefix   (or (second (re-matches #"(.*)\.csv$" filename))
                                  filename)
            table-name        (->> (str table-prefix filename-prefix)
                                   (unique-table-name driver)
                                   (u/lower-case-en))
            schema+table-name (table-identifier {:schema schema-name :name table-name})
            stats             (load-from-csv! driver (:id database) schema+table-name file)
            ;; Sync immediately to create the Table and its Fields; the scan is settings-dependent and can be async
            table             (sync-tables/create-or-reactivate-table! database {:name table-name :schema (not-empty schema-name)})
            _set_is_upload    (t2/update! :model/Table (:id table) {:is_upload true})
            _sync             (scan-and-sync-table! database table)
            ;; Set the display_name of the auto-generated primary key column to the same as its name, so that if users
            ;; download results from the table as a CSV and reupload, we'll recognize it as the same column
            auto-pk-field     (table-id->auto-pk-column (:id table))
            _                 (t2/update! :model/Field (:id auto-pk-field) {:display_name (:name auto-pk-field)})
            card              (card/create-card!
                               {:collection_id          collection-id
                                :type                   :model
                                :database_id            (:id database)
                                :dataset_query          {:database (:id database)
                                                         :query    {:source-table (:id table)}
                                                         :type     :query}
                                :display                :table
                                :name                   (humanization/name->human-readable-name filename-prefix)
                                :visualization_settings {}}
                               @api/*current-user*)
            upload-seconds    (/ (since-ms timer) 1e3)
            stats             (assoc stats :upload-seconds upload-seconds)]

        (events/publish-event! :event/upload-create
                               {:user-id  (:id @api/*current-user*)
                                :model-id (:id table)
                                :model    :model/Table
                                :details  {:db-id       db-id
                                           :schema-name schema-name
                                           :table-name  table-name
                                           :model-id    (:id card)
                                           :stats       stats}})

        (snowplow/track-event! ::snowplow/csv-upload-successful api/*current-user-id*
                               (assoc stats :model-id (:id card)))
        card)
      (catch Throwable e
        (snowplow/track-event! ::snowplow/csv-upload-failed api/*current-user-id* (fail-stats file))
        (throw e)))))

;;; +-----------------------------
;;; |  appending to uploaded table
;;; +-----------------------------

(defn- base-type->upload-type
  "Returns the most specific upload type for the given base type."
  [base-type]
  (when base-type
    (condp #(isa? %2 %1) base-type
      :type/Float                  ::float
      :type/BigInteger             ::int
      :type/Integer                ::int
      :type/Boolean                ::boolean
      :type/DateTimeWithTZ         ::offset-datetime
      :type/DateTime               ::datetime
      :type/Date                   ::date
      :type/Text                   ::text)))

(defn- not-blank [s]
  (when-not (str/blank? s)
    s))

(defn- extra-and-missing-error-markdown [extra missing]
  (when (seq missing)
    (->> [[(tru "The CSV file is missing columns that are in the table:") missing]
          ;; Even though we allow new columns to be implicitly added by uploads, we mention then in the error messages
          ;; for missing fields as a common case will be the misspelling of names. Seeing the actual and expected
          ;; names together could help customers spot the root cause more easily.
          [(tru "There are new columns in the CSV file that are not in the table:") extra]]
         (keep (fn [[header columns]]
                 (when (seq columns)
                   (str/join "\n" (cons header (map #(str "- " %) columns))))))
         (str/join "\n\n")
         (not-blank))))

(def ^:private allowed-type-upgrades
  "A mapping of which types a column can be implicitly relaxed to, based on the content of appended values."
  {::int #{::float}})

(defn- check-schema
  "Throws an exception if:
    - the CSV file contains duplicate column names
    - the schema of the CSV file does not match the schema of the table

    Note that we do not require the column ordering to be consistent between the header and the table schema."
  [fields-by-normed-name header]
  ;; Assumes table-cols are unique when normalized
  (let [normalized-field-names (keys fields-by-normed-name)
        normalized-header      (map normalize-column-name header)
        [extra missing _both]  (data/diff (set normalized-header) (set normalized-field-names))]
    ;; check for duplicates
    (when (some #(< 1 %) (vals (frequencies normalized-header)))
      (throw (ex-info (tru "The CSV file contains duplicate column names.")
                      {:status-code 422})))
    (when-let [error-message (extra-and-missing-error-markdown extra missing)]
      (throw (ex-info error-message {:status-code 422})))))

(defn- matching-or-upgradable? [current-type relaxed-type]
  (or (nil? current-type)
      (= current-type relaxed-type)
      (when-let [f (allowed-type-upgrades current-type)]
        (f relaxed-type))))

(defn- field-changes
  "Given existing and newly inferred types for the given `field-names`, calculate which fields need to be added or updated, along with their new types."
  [field-names existing-types new-types]
  (reduce
   (fn [m [f e n]]
     (cond
       (nil? e)   (assoc-in m [:added f] n)
       (not= e n) (assoc-in m [:updated f] n)
       :else      m))
   {:added {}, :updated {}}
   (map vector field-names existing-types new-types)))

(defn- field->db-type [driver field->col-type]
  (m/map-kv
   (fn [field-name col-type]
     [(keyword field-name)
      (driver/upload-type->database-type driver col-type)])
   field->col-type))

(defn- add-columns! [driver database table field->type & args]
  (when (seq field->type)
    (apply driver/add-columns! driver (:id database) (table-identifier table)
           (field->db-type driver field->type)
           args)))

(defn- alter-columns! [driver database table field->new-type & args]
  (when (seq field->new-type)
    (apply driver/alter-columns! driver (:id database) (table-identifier table)
           (field->db-type driver field->new-type)
            args)))

(defn- append-csv!*
  [database table file]
  (try
    (with-open [reader (bom/bom-reader file)]
      (let [timer              (start-timer)
            [header & rows]    (without-auto-pk-columns (csv/read-csv reader))
            driver             (driver.u/database->driver database)
            normed-name->field (m/index-by (comp normalize-column-name :name)
                                           (t2/select :model/Field :table_id (:id table) :active true))
            normed-header      (map normalize-column-name header)
            create-auto-pk?    (and
                                (driver/create-auto-pk-with-append-csv? driver)
                                (not (contains? normed-name->field auto-pk-column-name)))
            _                  (check-schema (dissoc normed-name->field auto-pk-column-name) header)
            settings           (upload-parsing/get-settings)
            old-types          (map (comp base-type->upload-type :base_type normed-name->field) normed-header)
            ;; in the happy, and most common, case all the values will match the existing types
            ;; for now we just plan for the worst and perform a fairly expensive operation to detect any type changes
            ;; we can come back and optimize this to an optimistic-with-fallback approach later.
            detected-types     (column-types-from-rows settings old-types rows)
            new-types          (map #(if (matching-or-upgradable? %1 %2) %2 %1) old-types detected-types)
            ;; avoid any schema modification unless we are able to fully upgrade to supporting the given file
            ;; choosing to not upgrade means that we will defer failure until we hit the first value that cannot
            ;; be parsed as its previous type - there is scope to improve these error messages in the future.
            modify-schema?     (and (not= old-types new-types) (= detected-types new-types))
            _                  (when modify-schema?
                                 (let [changes (field-changes normed-header old-types new-types)]
                                   (add-columns! driver database table (:added changes))
                                   (alter-columns! driver database table (:updated changes))))
            ;; this will fail if any of our required relaxations were rejected.
            parsed-rows        (parse-rows settings new-types rows)
            row-count          (count parsed-rows)
            stats              {:num-rows          row-count
                                :num-columns       (count new-types)
                                :generated-columns (if create-auto-pk? 1 0)
                                :size-mb           (file-size-mb file)
                                :upload-seconds    (since-ms timer)}]

        (try
          (driver/insert-into! driver (:id database) (table-identifier table) normed-header parsed-rows)
          (catch Throwable e
            (throw (ex-info (ex-message e) {:status-code 422}))))

        (when create-auto-pk?
          (add-columns! driver database table
                        {auto-pk-column-keyword ::auto-incrementing-int-pk}
                        :primary-key [auto-pk-column-keyword]))

        (scan-and-sync-table! database table)

        (when create-auto-pk?
          (let [auto-pk-field (table-id->auto-pk-column (:id table))]
            (t2/update! :model/Field (:id auto-pk-field) {:display_name (:name auto-pk-field)})))

        (events/publish-event! :event/upload-append
                               {:user-id  (:id @api/*current-user*)
                                :model-id (:id table)
                                :model    :model/Table
                                :details  {:db-id       (:id database)
                                           :schema-name (:schema table)
                                           :table-name  (:name table)
                                           :stats       stats}})

        (snowplow/track-event! ::snowplow/csv-append-successful api/*current-user-id* stats)

        {:row-count row-count}))
    (catch Throwable e
      (snowplow/track-event! ::snowplow/csv-append-failed api/*current-user-id* (fail-stats file))
      (throw e))))

(defn- can-append-error
  "Returns an ExceptionInfo object if the user cannot upload to the given database and schema. Returns nil otherwise."
  [db table]
  (or (can-use-uploads-error db)
      (cond
        (not (:is_upload table))
        (ex-info (tru "The table must be an uploaded table.")
                 {:status-code 422})

        (not (mi/can-read? table))
        (ex-info (tru "You don''t have permissions to do that.")
                 {:status-code 403}))))

(defn- check-can-append
  "Throws an error if the user cannot upload to the given database and schema."
  [db table]
  (when-let [error (can-append-error db table)]
    (throw error)))

(defn can-upload-to-table?
  "Returns true if the user can upload to the given database and table, and false otherwise."
  [db table]
  (nil? (can-append-error db table)))

;;; +--------------------------------------------------
;;; |  public interface for appending to uploaded table
;;; +--------------------------------------------------

(mu/defn append-csv!
  "Main entry point for appending to uploaded tables with a CSV file.
  This will create an auto-incrementing primary key (auto-pk) column in the table for drivers that supported uploads
  before auto-pk columns were introduced by metabase#36249."
  [{:keys [^File file table-id]}
   :- [:map
       [:table-id ms/PositiveInt]
       [:file (ms/InstanceOfClass File)]]]
  (let [table    (api/check-404 (t2/select-one :model/Table :id table-id))
        database (table/database table)]
    (check-can-append database table)
    (append-csv!* database table file)))

;;; +--------------------------------
;;; |  hydrate based_on_upload for FE
;;; +--------------------------------

(defn uploadable-table-ids
  "Returns the subset of table ids where the user can upload to the table."
  [table-ids]
  (set (when (seq table-ids)
         (->> (t2/hydrate (t2/select :model/Table :id [:in table-ids]) :db)
              (filter #(can-upload-to-table? (:db %) %))
              (map :id)))))

(defn- no-joins?
  "Returns true if `query` has no joins in it, otherwise false."
  [query]
  (let [all-joins (mapcat (fn [stage]
                            (lib/joins query stage))
                          (range (lib/stage-count query)))]
    (empty? all-joins)))

(mu/defn model-hydrate-based-on-upload
  "Batch hydrates `:based_on_upload` for each item of `models`. Assumes each item of `model` represents a model."
  [models :- [:sequential [:map
                           ;; query_type and dataset_query can be null in tests, so we make them nullable here.
                           ;; they should never be null in production
                           [:dataset_query [:maybe ms/Map]]
                           [:query_type    [:maybe [:or :string :keyword]]]
                           [:table_id      [:maybe ms/PositiveInt]]
                           ;; is_upload can be provided for an optional optimization
                           [:is_upload {:optional true} [:maybe :any]]]]]
  (let [table-ids             (->> models
                                   ;; as an optimization when listing collection items (GET /api/collection/items),
                                   ;; we might already know that the table is not an upload if is_upload=false. We
                                   ;; can skip making more queries if so
                                   (remove #(false? (:is_upload %)))
                                   (keep :table_id)
                                   set)
        mbql?                 (fn [model] (= "query" (name (:query_type model "query"))))
        has-uploadable-table? (comp (uploadable-table-ids table-ids) :table_id)]
    (for [model models]
      (m/assoc-some
       model
       :based_on_upload
       (when-let [query (some-> model :dataset_query lib/->pMBQL not-empty)] ; dataset_query can be empty in tests
         (when (and (mbql? model) (has-uploadable-table? model) (no-joins? query))
           (lib/source-table-id query)))))))

(mi/define-batched-hydration-method based-on-upload
  :based_on_upload
  "Add based_on_upload=<table-id> to a card if:
    - the card is a model
    - the query is a GUI query, and does not have any joins
    - the base table of the card is based on an upload
    - the user has permissions to upload to the table
    - uploads are enabled
  Otherwise based_on_upload is nil."
  [cards]
  (let [id->model         (m/index-by :id (model-hydrate-based-on-upload (filter #(= (:type %) :model) cards)))
        card->maybe-model (comp id->model :id)]
    (map #(or (card->maybe-model %) %) cards)))
