(ns analyzer.analyzer
  (:require [analyzer.utils :as u]
            [analyzer.env :as env :refer [*env*]]))

(deftype ExceptionThrown [e ast])

(defn ^:private throw! [e]
  (throw (.e ^ExceptionThrown e)))



(defn empty-env
  "Returns an empty env map"
  []
  {:context    :ctx/expr
   :locals     {}
   :ns         (ns-name *ns*)})

(defn build-ns-map []
  (into {} (mapv #(vector (ns-name %)
                          {:mappings (merge (ns-map %) {'in-ns #'clojure.core/in-ns
                                                        'ns    #'clojure.core/ns})
                           :aliases  (reduce-kv (fn [a k v] (assoc a k (ns-name v)))
                                                {} (ns-aliases %))
                           :ns       (ns-name %)})
                 (all-ns))))

(defn global-env []
  (atom {:namespaces     (build-ns-map)

         :update-ns-map! (fn update-ns-map! []
                           (swap! *env* assoc-in [:namespaces] (build-ns-map)))}))





(defn update-ns-map! []
  ((get (env/deref-env) :update-ns-map! #())))


(defn analyze+eval
  "Like analyze but evals the form after the analysis and attaches the
   returned value in the :result field of the AST node.

   If evaluating the form will cause an exception to be thrown, the exception
   will be caught and wrapped in an ExceptionThrown object, containing the
   exception in the `e` field and the AST in the `ast` field.

   The ExceptionThrown object is then passed to `handle-evaluation-exception`,
   which by defaults throws the original exception, but can be used to provide
   a replacement return value for the evaluation of the AST.

   Unrolls `do` forms to handle the Gilardi scenario.

   Useful when analyzing whole files/namespaces."
  ([form] (analyze+eval form (empty-env) {}))
  ([form env] (analyze+eval form env {}))
  ([form env {:keys [handle-evaluation-exception]
              :or {handle-evaluation-exception throw!}
              :as opts}]
   (env/ensure (global-env)
               (update-ns-map!)
               (let [env (merge env (u/-source-info form env))
                     [mform raw-forms] (with-bindings {Compiler/LOADER     (RT/makeClassLoader)
                                                       #'*ns*              (the-ns (:ns env))
                                                       #'ana/macroexpand-1 (get-in opts [:bindings #'ana/macroexpand-1] macroexpand-1)}
                                         (loop [form form raw-forms []]
                                           (let [mform (ana/macroexpand-1 form env)]
                                             (if (= mform form)
                                               [mform (seq raw-forms)]
                                               (recur mform (conj raw-forms
                                                                  (if-let [[op & r] (and (seq? form) form)]
                                                                    (if (or (u/macro? op  env)
                                                                            (u/inline? op r env))
                                                                      (vary-meta form assoc ::ana/resolved-op (u/resolve-sym op env))
                                                                      form)
                                                                    form)))))))]
                 (if (and (seq? mform) (= 'do (first mform)) (next mform))
           ;; handle the Gilardi scenario
                   (let [[statements ret] (butlast+last (rest mform))
                         statements-expr (mapv (fn [s] (analyze+eval s (-> env
                                                                           (ctx :ctx/statement)
                                                                           (assoc :ns (ns-name *ns*)))
                                                                     opts))
                                               statements)
                         ret-expr (analyze+eval ret (assoc env :ns (ns-name *ns*)) opts)]
                     {:op         :do
                      :top-level  true
                      :form       mform
                      :statements statements-expr
                      :ret        ret-expr
                      :children   [:statements :ret]
                      :env        env
                      :result     (:result ret-expr)
                      :raw-forms  raw-forms})
                   (let [a (analyze mform env opts)
                         frm (emit-form a)
                         result (try (eval frm) ;; eval the emitted form rather than directly the form to avoid double macroexpansion
                                     (catch Exception e
                                       (handle-evaluation-exception (ExceptionThrown. e a))))]
                     (merge a {:result    result
                               :raw-forms raw-forms})))))))
