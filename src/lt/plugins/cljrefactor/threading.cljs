(ns lt.plugins.cljrefactor.threading
  (:require [clojure.zip :as z]
            [cljs.reader :as rdr]
            [clojure.string :as s]
            [lt.object :as object]
            [lt.objs.editor.pool :as pool]
            [lt.objs.editor :as editor]
            [lt.objs.command :as cmd])
  (:require-macros [lt.macros :refer [defui behavior]]))




;; TODO: read-string doesn't handle nearly all of clojure !
(defn str->seq-zip [form-str]
  (rdr/register-tag-parser! "afn" (fn [x] (str "___#" x "___"))) ;; hack to escape anonymous functions
  (let [res (when (seq form-str)
              (-> form-str
                  (s/replace #"#\(" "#afn(")
                  rdr/read-string
                  z/seq-zip))]
    (rdr/deregister-tag-parser! "afn")
    res))


(defn zip->str [zipnode]
  (-> zipnode
      z/root
      pr-str
      (s/replace #"\"___|___\"" "")))

(defn top [zipnode]
  (loop [n zipnode]
    (if-not (z/up n)
      n
      (recur (z/up n)))))


(defn threading-locator [t]
  (case t
    ("->" "some->")     #(-> % z/down z/right z/down z/right)
    ("->>" "some->>")   #(-> % z/down z/right z/down z/rightmost)
    :else nil))

(defn unwind-op [t]
  (case t
    ("->" "some->")     #(-> %1 z/down (z/insert-right %2))
    ("->>" "some->>")   #(z/append-child % %2)
    :else nil))


(defn threaded? [zipnode]
  (when-let [f (z/down zipnode)]
    (some #{"->" "->>" "some->" "some->>"} [(str (z/node f))])))



(defn wrap-in-thread [zipnode t]
  (-> (list (rdr/read-string t))
      z/seq-zip
      z/down
      (z/insert-right (z/node zipnode))
      z/up))

(defn unwrap-list-if-one [node]
  ;; if somehow node was a vector this would croak, somewhere along the way a node got from list to a lazy-seq ...
  (if (and (seq node ) (= (count node) 1))
    (-> node z/seq-zip z/down z/node)
    node))



(defn further-threadable? [cand]
  (list? (-> cand z/down z/right z/node)))

(defn do-thread-one [cand cand-fn]
  (if-not (further-threadable? cand)
    cand
    (let [promote (-> cand cand-fn z/node)
          therest (-> cand cand-fn z/remove)]
      (-> therest
          z/up
          (z/insert-left promote)
          (#(z/replace % (unwrap-list-if-one (z/node %))))
          z/up))))

(defn- do-thread [orig cand-fn t]
  (when (seq orig)
    (let [root (if (threaded? orig) orig (wrap-in-thread orig t))]
      (loop [cand root]
        (if-not (further-threadable? cand)
          cand
          (recur (do-thread-one cand cand-fn)))))))


(defn thread-first [form-str]
  (when-let [node (str->seq-zip form-str)]
    (-> node
        (do-thread (threading-locator "->") "->")
        zip->str)))

(defn thread-last [form-str]
  (when-let [node (str->seq-zip form-str)]
    (-> node
        (do-thread (threading-locator "->>") "->>")
        zip->str)))


(defn thread [form-str]
  (let [node (str->seq-zip form-str)
        threading (when node (threaded? node))]
    (when (and node threading)
      (-> node
          (do-thread (threading-locator threading) threading)
          zip->str))))

(defn thread-one [form-str]
  (let [node (str->seq-zip form-str)
        threading (when node (threaded? node))]
    (when (and node threading)
      (-> node
          (do-thread-one (threading-locator threading))
          zip->str))))

(defn unwrap-threading [zipnode]
  (-> zipnode  z/down z/right z/node z/seq-zip))

(defn maybe-wrap-in-list [node]
  (if (list? node)
    node
    (z/node (z/seq-zip (list node)))))


(defn further-unwindable? [zipnode]
  (> (count (-> zipnode z/children )) 2))


(defn maybe-unwrap-threading [zipnode]
  (if-not (further-unwindable? zipnode)
    (unwrap-threading zipnode)
    zipnode))

(defn do-unwind-one [cand unwind-fn]
  (if-not (further-unwindable? cand)
    cand
    (let [demote (-> cand z/down z/right z/node)
          therest (-> cand z/down z/right z/remove)]
      (-> therest
          z/right
          (#(z/replace % (maybe-wrap-in-list (z/node %)))) ; Ensure list before demoting
          (unwind-fn demote)
          top))))


(defn do-unwind [root unwind-fn]
  (loop [cand root]
    (if-not (further-unwindable? cand)
      cand
      (recur (do-unwind-one cand unwind-fn)))))

(defn unwind [form-str]
 (let [node (str->seq-zip form-str)
       threading (when node (threaded? node))]
   (when (and node threading)
     (-> node
         (do-unwind (unwind-op threading))
         (unwrap-threading)
         zip->str))))


(defn unwind-one [form-str]
  (let [node (str->seq-zip form-str)
        threading (when node (threaded? node))]

    (when (and node threading)
      (-> node
          (do-unwind-one (unwind-op threading))
          maybe-unwrap-threading
          zip->str))))



(defn replace-cmd [ed replace-fn]
  (cmd/exec! :paredit.select.parent)
  (when-let [candidate  (editor/selection ed)]
    (let [bounds (editor/selection-bounds ed)]
      (when-let [res (replace-fn candidate)]
        (editor/replace-selection ed res))
      (editor/move-cursor ed (-> bounds :from (update-in [:ch] inc))))))


(behavior ::thread-fully!
          :triggers #{:refactor.thread-fully!}
          :reaction (fn [ed]
                      (replace-cmd ed thread)))

(behavior ::thread-one!
          :triggers #{:refactor.thread-one!}
          :reaction (fn [ed]
                      (replace-cmd ed thread-one)))


(behavior ::thread-first-fully!
          :triggers #{:refactor.thread-first-fully!}
          :reaction (fn [ed]
                      (replace-cmd ed thread-first)))

(behavior ::thread-last-fully!
          :triggers #{:refactor.thread-last-fully!}
          :reaction (fn [ed]
                      (replace-cmd ed thread-last)))


(behavior ::unwind-fully!
          :triggers #{:refactor.unwind-fully!}
          :reaction (fn [ed]
                      (replace-cmd ed unwind)))

(behavior ::unwind-one!
          :triggers #{:refactor.unwind-one!}
          :reaction (fn [ed]
                      (replace-cmd ed unwind-one)))




(cmd/command {:command ::thread-fully
              :desc "Clojure refactor: Thread fully"
              :exec (fn []
                      (when-let [ed (pool/last-active)]
                        (object/raise ed :refactor.thread-fully!)))})

(cmd/command {:command ::thread-one
              :desc "Clojure refactor: Thread one"
              :exec (fn []
                      (when-let [ed (pool/last-active)]
                        (object/raise ed :refactor.thread-one!)))})

(cmd/command {:command ::thread-first-fully
              :desc "Clojure refactor: Thread first fully"
              :exec (fn []
                      (when-let [ed (pool/last-active)]
                        (object/raise ed :refactor.thread-first-fully!)))})

(cmd/command {:command ::thread-last-fully
              :desc "Clojure refactor: Thread last fully"
              :exec (fn []
                      (when-let [ed (pool/last-active)]
                        (object/raise ed :refactor.thread-last-fully!)))})

(cmd/command {:command ::unwind-fully
              :desc "Clojure refactor: Unwind fully"
              :exec (fn []
                      (when-let [ed (pool/last-active)]
                        (object/raise ed :refactor.unwind-fully!)))})

(cmd/command {:command ::unwind-one
              :desc "Clojure refactor: Unwind one"
              :exec (fn []
                      (when-let [ed (pool/last-active)]
                        (object/raise ed :refactor.unwind-one!)))})


(comment
  (thread "(-> (assoc (assoc {:a 1} :b 2) :c 3))")
  (thread "(->> (map inc (filter even? [1 2 3 4 5])))")
  (thread "(-> (:c (:b (:a {:a {:b {:c 1}}}))))")
  (thread-one "(-> (:c (:b (:a {:a {:b {:c 1}}}))))")
  (thread-one "(-> (:b (:a {:a {:b {:c 1}}})) :c)")
  (thread-one "(-> (:a {:a {:b {:c 1}}}) :b :c)")
  (thread-one "(-> {:a {:b {:c 1}}} :a :b :c)")
  (thread-one "(-> {:a 1})")

  (unwind "(->> [1 2 3 4 5] (filter even?) (map inc))")
  (unwind "(-> {:a {:b {:c 1}}} :a :b :c)")
  (unwind-one "(-> {:a {:b {:c 1}}} :a :b :c)")
  (unwind-one "(-> (:a {:a {:b {:c 1}}}) :b :c)")
  (unwind-one "(-> (:b (:a {:a {:b {:c 1}}})) :c)")


  (thread-one "(some-> (assoc (assoc {:a 1} :b 2) :c 3))")
  (thread "(->> (map #(+ % 1) (filter even? [1 2 3 4 5])))"))



