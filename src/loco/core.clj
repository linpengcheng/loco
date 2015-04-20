(ns loco.core
  (:import (org.chocosolver.solver.variables VF IntVar)
           (org.chocosolver.solver.exception SolverException)
           (org.chocosolver.solver ResolutionPolicy
                                   Solver)
           org.chocosolver.solver.constraints.Constraint
           (org.chocosolver.solver.search.strategy ISF
                                                   strategy.AbstractStrategy)
           (org.chocosolver.solver.search.loop.monitors SMF)
           (org.chocosolver.util ESat)))

(defn- namey?
  [x]
  (try (boolean (name x))
    (catch Exception e
      false)))

(defn- id
  []
  (gensym "id"))

(def ^:dynamic *solver*
  "(internal) Bound to the Loco solver object during problem construction / solving"
  nil)

(defmulti eval-constraint-expr*
  "Evaluates a map data structure in the correct behavior, typically returning a constraint or a variable."
  (fn [data]
    (if (and (vector? data)
             (keyword? (first data)))
      :vector-var-name
      (or (:type data) (type data)))))

(defn- intersect-domains
  [d1 d2]
  (cond
    (and (not (map? d1))
         (not (map? d2))) (filter (set d1) d2)
    (and (not (map? d1))
         (map? d2)) (let [{lo :min hi :max} d2]
                      (filter #(<= lo % hi) d1))
    (and (map? d1)
         (not (map? d2))) (recur d2 d1)
    :else (let [{lo1 :min hi1 :max b1? :bounded} d1
                {lo2 :min hi2 :max b2? :bounded} d2
                m {:min (max lo1 lo2)
                   :max (min hi1 hi2)}]
            (if (and b1? b2?)
              (assoc m :bounded true)
              m))))

(defn- top-level-var-declarations
  "finds top-level domain declarations, merges them per-variable,
and returns a list of variable declarations"
  [data]
  (let [domain-decls (filter :can-init-var data)
        all-domains (group-by :name domain-decls)]
    (for [[var-name decls] all-domains
          :let [final-domain (reduce intersect-domains (map :domain decls))]]
      (if (if (map? final-domain)
            (= final-domain {:min 0 :max 1})
            (= #{0 1} (set final-domain)))
        {:type :bool-var
         :name var-name
         :real-name (name (gensym "bool-var"))}
        {:type :int-var
         :name var-name
         :real-name (name (gensym "int-var"))
         :domain (reduce intersect-domains (map :domain decls))}))))

(defn- without-top-level-var-declarations
  [data]
  (remove :can-init-var data))

(defrecord LocoSolver
  [csolver memo-table my-vars n-solutions])

(defn- new-solver
  []
  (->LocoSolver
    (Solver. (str (gensym "solver")))
    (atom {})
    (atom {})
    (atom 0)))

(defn- find-int-var
  [solver n]
  (or (@(:my-vars solver) n)
      (throw (IllegalAccessException. (str "var with name " n
                                           " doesn't have a corresponding "
                                           "\"$in\" call in the top-level"
                                           " of the problem")))))

(defn- get-val
  [v]
  (.getLB v))

(defn eval-constraint-expr
  "Memoized version of eval-constraint-expr*"
  [data]
  (let [lookup (when (:id data)
                 (@(:memo-table *solver*) (:id data)))]
    (if lookup
      lookup
      (let [result (eval-constraint-expr* data)]
        (when (:id data)
          (swap! (:memo-table *solver*) assoc (:id data) result))
        result))))

(defmethod eval-constraint-expr* :default
  [data]
  data)

(defmethod eval-constraint-expr* clojure.lang.Keyword
  [data]
  (find-int-var *solver* data))

(defmethod eval-constraint-expr* :vector-var-name
  [data]
  (find-int-var *solver* data))

(defn- return-next-solution
  []
  (let [n (dec @(:n-solutions *solver*))]
    (into (with-meta {} {:loco/solution n})
          (for [[var-name v] @(:my-vars *solver*)
                :when (if (keyword? var-name)
                        (not= (first (name var-name)) \_)
                        (not= (first (name (first var-name))) \_))]
            [var-name (get-val v)]))))

(defn- constrain!
  [constraint]
  (.post (:csolver *solver*) constraint))

(defn- problem->solver
  [problem]
  (let [problem (concat (top-level-var-declarations problem)
                        (without-top-level-var-declarations problem)) ; dig for the var declarations and put them at the front
        s (new-solver)]
    (binding [*solver* s]
      (doseq [i problem
              :let [i (eval-constraint-expr i)]]
        (when (instance? Constraint i)
          (constrain! i)))
      (let [vars (vals @(:my-vars s))
            strategy (ISF/minDom_LB (into-array IntVar vars))]
        (.set (:csolver s) (into-array AbstractStrategy [strategy])))
      s)))

(defn- feasible?
  "After the problem has executed, determines whether the problem was feasible"
  []
  (let [f (.isFeasible (:csolver *solver*))]
    (condp = f
      ESat/TRUE true
      ESat/FALSE false
      ESat/UNDEFINED (throw (Exception. "Solver has not been run yet")))))

(defn- solve!
  [args]
  (let [n-atom (:n-solutions *solver*)
        csolver (:csolver *solver*)]
    (when (:timeout args)
      (SMF/limitTime csolver (long (:timeout args))))
    (cond
      (:maximize args) (do (.findOptimalSolution csolver ResolutionPolicy/MAXIMIZE
                                                 (eval-constraint-expr (:maximize args)))
                           (and (feasible?)
                                (swap! n-atom inc)
                                true))
      (:minimize args) (do (.findOptimalSolution csolver ResolutionPolicy/MINIMIZE
                                                 (eval-constraint-expr (:minimize args)))
                           (and (feasible?)
                                (swap! n-atom inc)
                                true))
      :else (if (= @n-atom 0)
              (and (.findSolution csolver)
                   (swap! n-atom inc)
                   true)
              (and (.nextSolution csolver)
                   (swap! n-atom inc)
                   true)))))

(defn solution*
  [args]
  (when (solve! args)
    (return-next-solution)))

(defn solution
  "Solves the problem using the specified constraints and returns a map from variable names to their values (or nil if there is no solution).
Keyword arguments:
- :maximize <var> - finds the solution maximizing the given variable.
- :minimize <var> - finds the solution minimizing the given variable.
- :feasible true - optimizes time by guaranteeing that the problem is feasible before trying to maximize/minimize a variable.
- :timeout <number> - stops after a certain amount of milliseconds (returns nil, or best solution so far when min/maxing a variable)
Note: returned solution maps have the metadata {:loco/solution <n>} denoting that it is the nth solution found (starting with 0)."
  [problem & args]
  (binding [*solver* (problem->solver problem)]
    (let [args (apply hash-map args)]
      (solution* args))))

(defn solutions
  "Solves the solver using the constraints and returns a lazy seq of maps (for each solution) from variable names to their values.
Keyword arguments:
- :timeout <number> - the lazy sequence ends prematurely if the timer exceeds a certain number of milliseconds.
(note: the clock starts ticking when you call 'first' on the resulting lazy-seq. I recommend calling 'doall' to avoid erratic lazy behavior.)"
  [problem & args]
  (let [solver (problem->solver problem)
        args (apply hash-map args)
        timeout (:timeout args)
        args (dissoc args :timeout)]
    (when timeout
      (SMF/limitTime (:csolver solver) timeout))
    (take-while identity
                (repeatedly #(binding [*solver* solver]
                               (solution* args))))))
