(ns build
  (:refer-clojure :exclude [test])
  (:require [clojure.tools.build.api :as b]
            [clojure.string :as str]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'net.clojars.jazzytomato/pg-realtime)
(def version "0.1.0")
(def class-dir "target/classes")

(defn bump-version
  "Bump the version in build.clj and README.md, then create a git tag.

   Usage: clojure -T:build bump-version :type :minor

   Supported types:
     :patch - 0.1.0 -> 0.1.1
     :minor - 0.1.0 -> 0.2.0
     :major - 0.1.0 -> 1.0.0"
  [{:keys [type] :or {type :patch}}]
  (let [version-pattern #"(\d+)\.(\d+)\.(\d+)"
        [_ major minor patch] (re-find version-pattern version)

        new-version
        (case type
          :patch (format "%s.%s.%d" major minor (inc (Integer/parseInt patch)))
          :minor (format "%s.%d.0" major (inc (Integer/parseInt minor)))
          :major (format "%d.0.0" (inc (Integer/parseInt major))))

        build-file "build.clj"
        build-content (slurp build-file)
        updated-build-content (str/replace build-content
                                           (re-pattern (str "def version \"" version "\""))
                                           (str "def version \"" new-version "\""))

        readme-file "README.md"
        readme-content (slurp readme-file)
        updated-readme-content (-> readme-content
                                   ;; lein
                                   (str/replace
                                    (re-pattern (str "\\[com.github.jazzytomato/pg-realtime \"" version "\"\\]"))
                                    (str "[com.github.jazzytomato/pg-realtime \"" new-version "\"]"))
                                   ;; deps.edn
                                   (str/replace
                                    (re-pattern (str "com.github.jazzytomato/pg-realtime \\{:mvn/version \"" version "\"\\}"))
                                    (str "com.github.jazzytomato/pg-realtime {:mvn/version \"" new-version "\"}")))]

    (spit build-file updated-build-content)
    (spit readme-file updated-readme-content)

    (let [{:keys [exit]} (b/process {:command-args ["git" "add" build-file readme-file]
                                     :dir "."
                                     :out :capture})]
      (when-not (zero? exit)
        (throw (ex-info "Failed to git add files" {}))))

    (let [{:keys [exit]} (b/process {:command-args ["git" "commit" "-m" (str "Bump version to " new-version)]
                                     :dir "."
                                     :out :capture})]
      (when-not (zero? exit)
        (throw (ex-info "Failed to commit version change" {}))))

    (let [{:keys [exit]} (b/process {:command-args ["git" "tag" "-a" new-version "-m" (str "Release " new-version)]
                                     :dir "."
                                     :out :capture})]
      (when-not (zero? exit)
        (throw (ex-info "Failed to create git tag" {}))))

    (print new-version)
    new-version))

(defn test "Run all the tests." [opts]
  (let [basis    (b/create-basis {:aliases [:test]})
        cmds     (b/java-command
                  {:basis      basis
                   :main      'clojure.main
                   :main-args ["-m" "cognitect.test-runner"]})
        {:keys [exit]} (b/process cmds)]
    (when-not (zero? exit) (throw (ex-info "Tests failed" {}))))
  opts)

(defn- pom-template [version]
  [[:description "PostgreSQL queries that automatically re-run when relevant data changes."]
   [:url "https://github.com/jazzytomato/pg-realtime"]
   [:licenses
    [:license
     [:name "Eclipse Public License"]
     [:url "http://www.eclipse.org/legal/epl-v10.html"]]]
   [:developers
    [:developer
     [:name "Tom Haratyk"]]]
   [:scm
    [:url "https://github.com/jazzytomato/pg-realtime"]
    [:connection "scm:git:https://github.com/jazzytomato/pg-realtime.git"]
    [:developerConnection "scm:git:ssh:git@github.com:jazzytomato/pg-realtime.git"]
    [:tag version]]])

(defn- jar-opts [opts]
  (assoc opts
         :lib lib   :version version
         :jar-file  (format "target/%s-%s.jar" lib version)
         :basis     (b/create-basis {})
         :class-dir class-dir
         :target    "target"
         :src-dirs  ["src"]
         :pom-data  (pom-template version)))

(defn ci "Run the CI pipeline of tests (and build the JAR)." [opts]
  (test opts)
  (b/delete {:path "target"})
  (let [opts (jar-opts opts)]
    (println "\nWriting pom.xml...")
    (b/write-pom opts)
    (println "\nCopying source...")
    (b/copy-dir {:src-dirs ["resources" "src"] :target-dir class-dir})
    (println "\nBuilding JAR..." (:jar-file opts))
    (b/jar opts))
  opts)

(defn install "Install the JAR locally." [opts]
  (let [opts (jar-opts opts)]
    (b/install opts))
  opts)

(defn deploy "Deploy the JAR to Clojars." [opts]
  (let [{:keys [jar-file] :as opts} (jar-opts opts)]
    (dd/deploy {:installer :remote :artifact (b/resolve-path jar-file)
                :pom-file (b/pom-path (select-keys opts [:lib :class-dir]))}))
  opts)
