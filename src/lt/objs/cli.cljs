(ns lt.objs.cli
  "Provide behaviors for commandline parsing and opening files from
  commandline or file manager"
  (:require [lt.object :as object]
            [lt.objs.app :as app]
            [lt.objs.files :as files]
            [lt.objs.workspace :as workspace]
            [lt.objs.platform :as platform]
            [lt.objs.command :as cmd]
            [clojure.string :as string]
            [lt.util.ipc :as ipc]
            [lt.objs.opener :as opener])
  (:require-macros [lt.macros :refer [behavior]]))

(def remote (.-remote (js/require "electron")))

(defn open-paths [path-line-pairs add?]
  (doseq [[path line] path-line-pairs
          :when (not= path (.-execPath js/process))]
    (if (files/exists? path)
      (if (files/dir? path)
        (object/raise workspace/current-ws :add.folder! path)
        (do
          (object/raise opener/opener :open! path)
          (when line
            (cmd/exec! :go-to-line line))
          (when add?
            (object/raise workspace/current-ws :add.file! path))))
      (object/raise opener/opener :new! path))))

(def parsed-args "Map of commandline options parsed by optimist"
  (js->clj (.getGlobal remote "browserParsedArgs") :keywordize-keys true))

(def open-files "Files to open from a file manager"
  (js->clj (.getGlobal remote "browserOpenFiles")))

(def argv "Arguments used to start LightTable" (js->clj (.-argv (.-process remote))))

(ipc/on "openFileAfterStartup" #(object/raise app/app :open! %))

(defn args
  "Returns path arguments passed to executable or nil if none given. Only returns
  on first window since subsequent windows don't open path arguments."
  []
  (and (app/first-window?)
       (or (when-let [argv-args (seq (if js/process.env.LT_DEV_CLI (subvec argv 2) (rest argv)))]
             ;; When opening LT with a file from a file manager, process.argv gets an apple event
             ;; as a second arg e.g. ["/path/to/electron" "-psn_0_12381134"]. This should be ignored
             ;; since file to open is in open-files
             (if (and (platform/mac?) (.startsWith (first argv-args) "-psn_"))
               (seq (rest argv-args)) argv-args))
           (seq open-files))))

;;*********************************************************
;; Behaviors
;;*********************************************************

(behavior ::open-on-args
          :triggers #{:post-init}
          :desc "App: Process commandline or file manager arguments"
          :reaction (fn [this]
                      (when (app/first-window?)
                        (let [path-line-pairs (map #(let [[_ path line] (re-find #"^(.*?):?(\d+)?$" %)]
                                                      [(files/resolve files/cwd path) line])
                                                   (args))
                              paths (map first path-line-pairs)
                              open-dir? (some files/dir? paths)]
                          (when open-dir?
                            (object/merge! workspace/current-ws {:initialized? true})
                            (cmd/exec! :workspace.show))
                          (open-paths path-line-pairs (:add parsed-args))))))

(behavior ::open!
          :triggers #{:open!}
          :desc "App: Open path(s) from a file manager after startup"
          :reaction (fn [this path]
                      (when (= (app/fetch :focusedWindow) (app/window-number))
                        (open-paths [[path]] (:add parsed-args)))))
