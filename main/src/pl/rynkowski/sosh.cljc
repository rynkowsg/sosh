#!/usr/bin/env bb

#?(:bb (do (require '[babashka.deps])
           (babashka.deps/add-deps '{:deps {;; Not in babashka. Keep in sync with :deps in deps.edn.
                                            pl.rynkowski.clj-gr/fs {:git/url "https://github.com/rynkowsg/clj-gr.git" :git/sha "80e9dc0bd21c538ff443d8edc7df85f89d589a82" :deps/root "lib/fs"}
                                            pl.rynkowski.clj-gr/lang {:git/url "https://github.com/rynkowsg/clj-gr.git" :git/sha "80e9dc0bd21c538ff443d8edc7df85f89d589a82" :deps/root "lib/lang"}
                                            #_:deps}})))

(ns pl.rynkowski.sosh
  (:require
    [babashka.cli :as cli]
    [babashka.fs :as fs]
    [babashka.process :refer [shell]]
    [clojure.pprint :refer [pprint]]
    [clojure.string :as str]
    [org.httpkit.client :as hk-client]
    [pl.rynkowski.clj-gr.fs :refer [with-cwd]]
    [pl.rynkowski.clj-gr.lang.coll :refer [some-insight]]
    [pl.rynkowski.clj-gr.lang.map :refer [vals->keys]]
    [pl.rynkowski.clj-gr.lang.regex :refer [named-groups]])
  (:import
    (java.net URL)))

;; ---------- CORE --------------------

;(defn- log [& data]
;  (spit "/tmp/debug-log.txt" (apply str (conj data "\n")) :append true))

(defn- log [& data]
  (apply println data))

(defn extract-interpreter [lines]
  (let [pattern #"^#!(.*)"
        matcher (re-matcher pattern (first lines))]
    (if-let [match (re-find matcher)]
      (second match)
      nil)))

(defn resolve-interpreter [lines]
  (let [extracted (extract-interpreter lines)]
    (cond
      (nil? extracted) "/bin/bash"
      (str/includes? extracted "bats") "/bin/bash"
      :else extracted)))

^:rct/test
(comment #_((requiring-resolve 'com.mjdowney.rich-comment-tests/run-ns-tests!) *ns*)
  (extract-interpreter ["#!/usr/bin/env bash" "echo"]) ;=> "/usr/bin/env bash"
  (extract-interpreter ["echo"]) ;=> nil
  (resolve-interpreter ["#!/usr/bin/env bash" "echo"]) ;=> "/usr/bin/env bash"
  (resolve-interpreter ["#!/usr/bin/env bats" "echo"]) ;=> "/bin/bash"
  (resolve-interpreter ["echo"]) ;=> "/bin/bash"
  :rct/test)

(defn resolve-path
  [{:keys [debug? lines-read script-path path-in-code root-path] :as opts}]
  (when debug? (log {:fn :resolve-path :opts (-> opts (dissoc :lines-read) (assoc :lines-read-count (count lines-read)))}))
  (let [prefix (format "%s.%s" (fs/file-name script-path) (str (System/currentTimeMillis)))
        ext (fs/extension root-path)
        ;; first create a temp file with the lines read
        lines-tmp-file (doto (str (fs/create-temp-file {:dir (fs/parent root-path)
                                                        :prefix (str prefix "-lines.")
                                                        :suffix (str "." ext)}))
                             (spit (str/join "\n" lines-read)))
        ;; then create a temp file with the path resolver code
        resolver-tmp-file (doto (str (fs/create-temp-file {:dir (fs/parent root-path)
                                                           :prefix (str prefix "-resolver.")
                                                           :suffix (str "." ext)}))
                            (spit (str/join "\n" [(format "source %s >/dev/null" lines-tmp-file)
                                                  (format "printf \"%%s\" %s" path-in-code)])))
        interpreter (resolve-interpreter lines-read)
        cmd (str interpreter " " resolver-tmp-file)
        _ (when debug? (log {:fn :resolve-path :cmd cmd}))
        {:keys [exit out] :as res} (shell {:out :string} cmd)]
    (if debug?
      (log {:fn :resolve-path :msg :completed :data {:lines-tmp-file lines-tmp-file
                                                     :resolver-tmp-file resolver-tmp-file
                                                     :cmd cmd
                                                     :res res}})
      (do (fs/delete lines-tmp-file)
          (fs/delete resolver-tmp-file)))
    (if (= exit 0)
      out
      (throw (ex-info "path resolution failed" {:opts opts :res res})))))

^:rct/test
(comment #_((requiring-resolve 'com.mjdowney.rich-comment-tests/run-ns-tests!) *ns*)
  "the path SCRIPT_DIR should match the path where the script is"
  (resolve-path {:script-path (str (fs/absolutize "./test/res/test_suite/3_import_with_variables/entry.bash"))
                 :root-path (str (fs/absolutize "./test/res/test_suite/3_import_with_variables/entry.bash"))
                 :lines-read ["#!/usr/bin/env bash"
                              "SCRIPT_DIR=\"$(cd \"$(dirname \"${BASH_SOURCE[0]}\")\" && pwd -P || exit 1)\""]
                 :path-in-code "${SCRIPT_DIR}/lib/lib1.bash"})
  ;=>> (str (fs/normalize (fs/path (fs/cwd) "./test/res/test_suite/3_import_with_variables/lib/lib1.bash")))
  :rct/test)

(def remote->regex
  (delay (let [dir "(?<dir>[^@]*)"
               path "(?<path>.*)"
               github-token "(?<token>(@github|\\.github([-_]deps){0,1}))"
               https-token "(?<token>(@https|\\.https([-_]deps){0,1}))"]
           {:github (re-pattern (str "^" dir "/" github-token "/" "(?<user>[^@/]*)/(?<name>[^@/]*)(@(?<ref>[^/]*))?" "/" path "$"))
            :https (re-pattern (str "^" dir "/" https-token "/" path "$"))})))
(def regex->remote (delay (vals->keys @remote->regex)))
^:rct/test
(comment #_((requiring-resolve 'com.mjdowney.rich-comment-tests/run-ns-tests!) *ns*)
  (re-matches (:github @remote->regex) "/repo/.sosh_deps/@github/rynkowsg/shell-gr@81b70c3da598456200d9c63fda779a04012ff256/lib/log_utils.bash") ;=>> some?
  (re-matches (:github @remote->regex) "/repo/.github-deps/rynkowsg/shell-gr@81b70c3da598456200d9c63fda779a04012ff256/lib/log_utils.bash") ;=>> some?
  (re-matches (:https @remote->regex) "./lib/.sosh_deps/@https/raw.githubusercontent.com/rynkowsg/shell-gr/main/lib/trap.bash") ;=>> some?
  (re-matches (:https @remote->regex) "./lib/.https-deps/raw.githubusercontent.com/rynkowsg/shell-gr/main/lib/trap.bash") ;=>> some?
  :comment)

(defn assess-source-path
  "having source-path, if it looks like a dep, composes url to fetch it"
  [source-path]
  (let [local {:path source-path}
        remote (when-let [{:keys [el res]} (some-insight #(named-groups (val %) source-path) @remote->regex)]
                 (case (-> el first)
                   :github (let [{:keys [dir type user name ref path]} res]
                             {:url (format "https://raw.githubusercontent.com/%s/%s/%s/%s" user name (or ref "HEAD") path)})
                   :https (let [{:keys [dir type path]} res]
                            {:url (format "https://%s" path)})
                   nil))]
    (merge local remote)))

^:rct/test
(comment #_((requiring-resolve 'com.mjdowney.rich-comment-tests/run-ns-tests!) *ns*)
  (assess-source-path "./lib/@github/rynkowsg/shell-gr@main/lib/trap.bash")
  ;=> {:path "./lib/@github/rynkowsg/shell-gr@main/lib/trap.bash" :url "https://raw.githubusercontent.com/rynkowsg/shell-gr/main/lib/trap.bash"}
  (assess-source-path "./lib/@https/raw.githubusercontent.com/rynkowsg/shell-gr/main/lib/trap.bash")
  ;=> {:path "./lib/@https/raw.githubusercontent.com/rynkowsg/shell-gr/main/lib/trap.bash" :url "https://raw.githubusercontent.com/rynkowsg/shell-gr/main/lib/trap.bash"}
  (assess-source-path "./lib/trap.bash")
  ;=> {:path "./lib/trap.bash"}
  (assess-source-path "./lib/@github/rynkowsg/shell-gr/lib/trap.bash")
  ;=> {:path "./lib/@github/rynkowsg/shell-gr/lib/trap.bash" :url "https://raw.githubusercontent.com/rynkowsg/shell-gr/HEAD/lib/trap.bash"}
  :rct/test)

(defn download-file
  [{:keys [debug? url path] :as params}]
  (when debug? (log {:fn :download-file :msg :enter :params params}))
  (let [{:keys [body status] :as _res} @(hk-client/request {:method :get :url url})]
    (println url)
    (if (= status 200)
      (do (fs/create-dirs (fs/parent path))
          (fs/create-file path)
          (spit path body))
      (throw (ex-info "remote file not available" {:cause-kw :remote-file-not-available :url url :path path})))))

(defn process-fetch-file
  [{:keys [debug? lines-read-before path chain root-path] :or {lines-read-before []} :as opts}]
  (when debug? (log {:fn :process-fetch-file :msg :enter :opts (dissoc opts :lines-read-before)}))
  (let [content (-> (slurp path)
                    (str/split-lines))
        res (->> content
                 (reduce (fn [lines-read l]
                           (if (and (or (str/starts-with? l "source ") (str/starts-with? l ". "))
                                    (not (or (str/includes? l "shellpack skip") (str/includes? l "sosh skip"))))
                             ;; if source, download if necessary and go deeper
                             (let [[_ file-to-source] (str/split l #" ")
                                   ;; resolve variable within path by evaluating everything read until this point
                                   resolved-source-path (resolve-path {:debug? debug?
                                                                       :lines-read lines-read
                                                                       :path-in-code file-to-source
                                                                       :script-path path
                                                                       :root-path root-path})
                                   _ (when debug? (log {:fn :process-fetch-file :msg :resolved-source-path :resolved resolved-source-path}))
                                   ;; normalize the path
                                   normalized-source-path (str (if (fs/absolute? resolved-source-path)
                                                                 resolved-source-path
                                                                 (fs/normalize (fs/path (fs/cwd) resolved-source-path))))
                                   _ (when debug? (log {:fn :process-fetch-file :msg :normalized-source-path :data normalized-source-path}))
                                   ;; assess whether it is a downloadable source path
                                   {url :url assessed-path :path :as assessed} (assess-source-path normalized-source-path)
                                   _ (when debug? (log {:fn :process-fetch-file :msg :assessed-source-path :data assessed}))
                                   exists? (some-> assessed-path fs/exists?)
                                   ;; download
                                   _ (cond
                                       (and (some? url) (not exists?)) (download-file {:debug? debug? :path assessed-path :url url})
                                       exists? (when debug? (log {:fn :process-fetch-file :msg :source-file-exists :path assessed-path}))
                                       (not exists?) (throw (ex-info "source local file does not exist" {:cause-kw :source-local-file-does-not-exist
                                                                                                         :path assessed-path
                                                                                                         :chain chain
                                                                                                         :cwd (str (fs/cwd))})))
                                   lines (process-fetch-file {:debug? debug?
                                                              :lines-read-before lines-read
                                                              :chain (conj chain assessed-path)
                                                              :path assessed-path
                                                              :root-path root-path})]
                               (when debug? (log {:fn :process-fetch-file :msg :return :lines-read-count (count lines)}))
                               lines)
                             ;; if not a source line, just add a line
                             (conj (vec lines-read) l))
                           #_:fn)
                         lines-read-before))]
    res)
  #_:process-fetch-file)

(defn process-fetch
  [{:keys [debug? entry] :as opts}]
  (when debug? (log {:fn :process-fetch :msg :enter :opts opts}))
  (let [path (str (if (fs/absolute? entry) entry (fs/absolutize (fs/path (fs/cwd) entry))))
        _ (if (fs/exists? path)
            (when debug? (log {:fn :process-fetch :msg :file-exists :path path}))
            (throw (ex-info "file does not exist" {:cause-kw :file-does-not-exist :path path})))]
    (process-fetch-file {:debug? debug?
                         :path path
                         :root-path path
                         :chain [path]
                         :lines-read-before []})))

(comment
  (def test-case "./test/res/test_suite/3_import_with_variables")
  (def test-case "./test/res/test_suite/4_import_remote")
  (with-cwd (fs/absolutize test-case) (process-fetch {:debug? true :entry "entry.bash" :output "output.bash"}))
  (def test-case "./test/res/test_suite/5_remote_sourcing_same_repo")
  (with-cwd (fs/absolutize (fs/path test-case "repo")) (process-fetch {:debug? true :entry "entry.bash" :output "output.bash"}))
  :comment)

(defn process-pack-file
  [{:keys [debug? lines-read-before line path chain sourced-list root-path top?] :as opts}]
  (when debug? (log {:fn :process-pack-file :opts (dissoc opts :lines-read-before)}))
  (let [content (-> (slurp path)
                    (str/split-lines))
        init-acc {:lines-read []
                  :lines-read-all lines-read-before
                  :sourced-list sourced-list}
        res (->> content
                 (reduce (fn [{:keys [lines-read lines-read-all sourced-list] :as acc} l]
                           (if (not (or (str/starts-with? l "source ") (str/starts-with? l ". ")))
                             ;; if not a source line, just add a line
                             {:lines-read (conj (vec lines-read) l)
                              :lines-read-all (conj (vec lines-read-all) l)
                              :sourced-list sourced-list}
                             ;; if source, download if necessary and go deeper
                             (let [[_ file-to-source] (str/split l #" ")
                                   ;; resolve variable within path by evaluating everything read until this point
                                   resolved-source-path (resolve-path {:debug? debug?
                                                                       :root-path root-path
                                                                       :lines-read lines-read-all
                                                                       :path-in-code file-to-source
                                                                       :script-path path})
                                   _ (when debug? (log {:fn :process-pack-file :msg :resolved-source-path :resolved resolved-source-path}))
                                   ;; normalize the path
                                   normalized-source-path (str (if (fs/absolute? resolved-source-path)
                                                                 resolved-source-path
                                                                 (fs/normalize (fs/path (fs/cwd) resolved-source-path))))
                                   _ (when debug? (log {:fn :process-pack-file :msg :normalized-source-path :data normalized-source-path}))]
                               ;; continue only if the file was not yet sourced
                               (if (contains? sourced-list normalized-source-path)
                                 (do (when debug? (log {:fn :process-pack-file :msg :skip-sourcing-file}))
                                     (assoc acc :lines-read (conj (vec lines-read) (format "# %s # SKIPPED" l))))
                                 (let [_ (when debug? (log {:fn :process-pack-file :msg :continue-sourcing-file :data {:normalized-source-path normalized-source-path
                                                                                                                       :sourced-list sourced-list}}))
                                       res (process-pack-file {:debug? debug?
                                                               :lines-read-before lines-read-all
                                                               :line l
                                                               :chain (conj chain normalized-source-path)
                                                               :path normalized-source-path
                                                               :sourced-list sourced-list
                                                               :root-path root-path})]
                                   {:lines-read (vec (concat lines-read (:lines-read res)))
                                    :lines-read-all (:lines-read-all res)
                                    :sourced-list (conj sourced-list normalized-source-path)}))))
                           #_:fn)
                         init-acc))]
    (let [pr [[(when line (format "# %s # BEGIN" line))]
              (:lines-read res)
              [(when line (format "# %s # END" line))]]]
      {:lines-read (->> pr
                        (flatten)
                        (filter some?)
                        (vec))
       :lines-read-all (:lines-read-all res)
       :sourced-list (:sourced-list res)})))

(defn process-pack
  [{:keys [debug? entry output] :as opts}]
  (when debug? (log {:fn :process-pack :msg :enter :opts opts}))
  (let [absolute-entry (-> (if (fs/absolute? entry) entry (fs/absolutize (fs/path (fs/cwd) entry))) (fs/normalize) (str))
        absolute-output (-> (if (fs/absolute? output) output (fs/absolutize (fs/path (fs/cwd) output))) (fs/normalize) (str))
        _ (when debug? (log {:fn :process-pack :msg :paths-resolved :data {:absolute-entry absolute-entry
                                                                           :absolute-output absolute-output}}))
        _ (if (fs/exists? absolute-entry)
            (when debug? (log {:fn :process-pack :msg :file-exists :path absolute-entry}))
            (throw (ex-info "file does not exist" {:cause-kw :file-does-not-exist :path absolute-entry})))
        {:keys [lines-read] :as pack-file-res} (process-pack-file {:debug? debug?
                                                                   :lines-read-before []
                                                                   :chain [absolute-entry]
                                                                   :path absolute-entry
                                                                   :sourced-list #{}
                                                                   :root-path absolute-entry
                                                                   :top? true})]
    (fs/create-dirs (fs/parent absolute-output))
    (let [output-str (format "%s\n" (str/join "\n" lines-read))
          res (spit absolute-output output-str)]
      res)))

(comment
  (def test-case "./test/res/test_suite/0_nothing")
  (def test-case "./test/res/test_suite/1_import_two_files")
  (def test-case "./test/res/test_suite/2_import_nested_files")
  (def test-case "./test/res/test_suite/3_import_with_variables")
  (with-cwd (fs/absolutize test-case) (process-pack {:debug? true :entry "entry.bash" :output "output.bash"}))
  (slurp (str test-case "/output.bash")) := (slurp (str test-case "/expected.bash"))
  (def test-case "./test/res/test_suite/5_remote_sourcing_same_repo")
  (with-cwd (fs/absolutize (fs/path test-case "repo")) (process-pack {:debug? true :entry "entry.bash" :output "output.bash"}))
  :tests-end)

;; ---------- MAIN --------------------

(def cli-fetch-opts
  (delay {:spec {:entry {:alias :i
                         :coerce :string
                         :desc "Entry point to the script being processed."
                         :ref "<path>"
                         :require true}
                 :debug? {:alias :d
                          :coerce :boolean
                          :desc "Shows debug logs"}
                 :help {:alias :h
                        :coerce :boolean
                        :desc "Shows this message"}}}))

(defn cli-fetch
  [{:keys [fn dispatch opts]}]
  (let [defaults {}
        opts' (merge defaults opts)]
    (try
      (process-fetch opts')
      (catch Exception e
        (let [{:keys [cause-kw path parent-path] :as data} (ex-data e)]
          (case cause-kw
            :file-under-processing-does-not-exist (do (println "FETCH ERROR")
                                                      (println "File does not exist:" path " " :file-under-processing-does-not-exist)
                                                      (println "Requested by:       " (if parent-path parent-path "input param"))
                                                      (System/exit 11))
            :source-local-file-does-not-exist (do (println "FETCH ERROR")
                                                  (println "File does not exist:" path " " :source-local-file-does-not-exist)
                                                  (println "Requested by:       " (if parent-path parent-path "input param"))
                                                  (pprint (dissoc data :cause-kw))
                                                  (System/exit 12))
            :remote-file-not-available (do (println "FETCH ERROR")
                                           (println "Remote file does not exist")
                                           (pprint (dissoc data :cause-kw))
                                           (System/exit 13))
            :file-does-not-exist (do (println "FETCH ERROR")
                                     (println "File does not exist:" path " " :file-does-not-exist)
                                     (println "Requested by:       " (if parent-path parent-path "input param"))
                                     (System/exit 1))
            (do (println "FETCH ERROR: OTHER")
                (println (ex-data e))
                (println e)
                (throw e))))))))

(def ^:private make-default-output
  (delay (str (fs/path (fs/cwd) "bundle.bash"))))

(def cli-pack-opts
  (delay {:spec {:entry {:alias :i
                         :coerce :string
                         :desc "Entry point to the script being processed."
                         :ref "<path>"
                         :require true}
                 :output {:alias :o
                          :coerce :string
                          :default @make-default-output
                          :desc "Output path"
                          :ref "<path>"}
                 :debug? {:alias :d
                          :coerce :boolean
                          :default false
                          :desc "Shows debug logs"}
                 :help {:alias :h
                        :coerce :boolean
                        :desc "Shows this message"}}}))

(defn cli-pack
  [{:keys [fn dispatch opts]}]
  (let [defaults {:output @make-default-output}
        opts' (merge defaults opts)]
    (try
      (process-pack opts')
      (catch Exception e
        (let [{:keys [cause-kw path parent-path]} (ex-data e)]
          (case cause-kw
            :file-does-not-exist (do (println "PACK ERROR")
                                     (println "File does not exist:" path " " :file-does-not-exist)
                                     (println "Requested by:       " (if parent-path parent-path "input param"))
                                     (System/exit 1))
            (do (println "PACK ERROR: OTHER")
                (println (ex-data e))
                (println e)
                (throw e))))))))

;; When no column is asked for, babashka.cli derives the set from the spec, so a
;; spec where no option carries a :default gives rows one cell shorter than the
;; header below and format-table blows up on the missing cell. Naming the columns
;; keeps every row as wide as the header.
(def cli-help-columns [:alias :option :ref :default :desc])
(def cli-help-columns-names ["alias" "option" "ref" "default" "description"])

(defn cli-help
  [_]
  (println
    "MODES:"
    "\n\n"
    (str "fetch\n"
         (cli/format-table
           {:rows (concat [cli-help-columns-names]
                          (-> @cli-fetch-opts
                              (assoc :columns cli-help-columns)
                              (cli/opts->table)))
            :indent 0}))
    "\n\n"
    (str "pack\n"
         (cli/format-table
           {:rows (concat [cli-help-columns-names]
                          (-> @cli-pack-opts
                              (assoc :columns cli-help-columns)
                              (cli/opts->table)))
            :indent 0}))))
#_(cli-help nil)

(def cli-table
  (delay [{:cmds ["pack"] :fn cli-pack :spec (:spec @cli-pack-opts) :args->opts [:entry]}
          {:cmds ["fetch"] :fn cli-fetch :spec (:spec @cli-fetch-opts) :args->opts [:entry]}
          {:cmds [] :fn cli-help}]))

(defn -main [& args]
  (cli/dispatch @cli-table args {:error-fn
                                 (fn [{:keys [_spec type cause msg option] :as _data}]
                                   (if (= :org.babashka/cli type)
                                     (case cause
                                       :require
                                       (do (println (format "Missing required argument: %s\n" option))
                                           (cli-help nil)
                                           (System/exit 1))
                                       :validate
                                       (println
                                         (format "%s does not exist!\n" msg)))))}))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
