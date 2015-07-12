(ns comportexviz.viz-canvas
  (:require [comportexviz.viz-layouts :as lay
             :refer [layout-bounds
                     element-xy
                     fill-elements
                     group-and-fill-elements]]
            [reagent.core :as reagent :refer [atom]]
            [goog.dom :as dom]
            [comportexviz.dom :refer [offset-from-target]]
            [comportexviz.helpers :as helpers :refer [resizing-canvas tap-c]]
            [comportexviz.proxies :as proxy]
            [monet.canvas :as c]
            [org.nfrac.comportex.core :as core]
            [org.nfrac.comportex.protocols :as p]
            [org.nfrac.comportex.util :as util]
            [org.nfrac.comportex.cells :as cells]
            [clojure.set :as set]
            [cljs.core.async :as async :refer [<! put! chan]])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [comportexviz.macros :refer [with-cache]]))

(def blank-selection {:region nil
                      :layer nil
                      :dt 0
                      :col nil
                      :cell-seg nil})

;;; ## Colours

(defn hsl
  ([h s l] (hsl h s l 1.0))
  ([h s l a]
   (let [h2 (if (keyword? h)
              (case h
                :red 0
                :orange 30
                :yellow 60
                :yellow-green 90
                :green 120
                :blue 210
                :purple 270
                :pink 300)
              ;; otherwise angle
              h)]
     (str "hsla(" h2 ","
          (long (* s 100)) "%,"
          (long (* l 100)) "%,"
          a ")"))))

(defn grey
  [z]
  (let [v (long (* z 255))]
    (str "rgb(" v "," v "," v ")")))

(def state-colors
  {:background "#eee"
   :inactive "white"
   :inactive-syn "black"
   :growing (hsl :green 1.0 0.5)
   :disconnected (hsl :red 1.0 0.5)
   :active (hsl :red 1.0 0.5)
   :predicted (hsl :blue 1.0 0.5 0.5)
   :active-predicted (hsl :purple 1.0 0.4)
   :highlight (hsl :yellow 1 0.65 0.6)
   :temporal-pooling (hsl :green 1 0.5 0.4)
   })

(def default-viz-options
  {:input {:active true
           :predicted true
           :refresh-index 0}
   :columns {:active true
             :overlaps nil
             :boosts nil
             :active-freq nil
             :n-segments nil
             :predictive true
             :temporal-pooling true
             :refresh-index 0}
   :ff-synapses {:to :selected ;; :selected, :all, :none
                 :growing true
                 :inactive nil
                 :disconnected nil
                 :permanences true}
   :distal-synapses {:from :selected ;; :selected, :all, :none
                     :growing true
                     :inactive nil
                     :disconnected nil
                     :permanences true}
   :keep-steps 50
   ;; triggers a rebuild & redraw of the layouts when changed:
   :drawing {:display-mode :one-d ;; :one-d, :two-d
             :draw-steps 16
             :height-px nil ;; set on resize
             :width-px nil ;; set on resize
             :top-px 30
             :bit-w-px 4
             :bit-h-px 3
             :bit-shrink 0.85
             :col-d-px 5
             :col-shrink 0.85
             :cell-r-px 10
             :seg-w-px 30
             :seg-h-px 8
             :seg-h-space-px 55
             :h-space-px 45
             :anim-go? true
             :anim-every 1}})

(defn draw-image-dt
  [ctx lay dt img]
  (let [[x y] (lay/origin-px-topleft lay dt)]
    (c/draw-image ctx img x y)))

(defn all-layout-paths
  [m]
  (for [k [:inputs :regions]
        subk (if (= k :regions)
               (keys (k m))
               [nil])
        :let [path0 (if subk [k subk] [k])]
        id (keys (get-in m path0))]
    (conj path0 id)))

(defn reset-layout-caches
  [m]
  (reduce (fn [m path]
            (update-in m path vary-meta
                       (fn [mm]
                         (assoc mm ::cache (atom {})))))
          m
          (all-layout-paths m)))

(defn init-grid-layouts
  [model opts]
  (let [inputs (:inputs model)
        regions (:regions model)
        layerseq (mapcat (fn [rgn-id]
                           (map vector (repeat rgn-id)
                                (core/layers (regions rgn-id))))
                         (core/region-keys model))
        d-opts (:drawing opts)
        display-mode (:display-mode d-opts)
        spacer (:h-space-px d-opts)
        top-px (:top-px d-opts)
        height-px (- (:height-px d-opts) top-px)
        ;; for now draw inputs and layers in a horizontal stack
        [i-lays i-right]
        (reduce (fn [[lays left] inp-id]
                  (let [topo (p/topology (inputs inp-id))
                        lay (lay/grid-layout topo top-px left height-px d-opts
                                             true display-mode)]
                    [(assoc lays inp-id lay)
                     (+ (lay/right-px lay) spacer)]))
                [{} 6]
                (core/input-keys model))
        [r-lays r-right]
        (reduce (fn [[lays left] [rgn-id lyr-id]]
                  (let [topo (p/topology (get-in regions [rgn-id lyr-id]))
                        lay (lay/grid-layout topo top-px left height-px d-opts
                                             false display-mode)]
                    [(assoc-in lays [rgn-id lyr-id] lay)
                     (+ (lay/right-px lay) spacer)]))
                [{} i-right]
                layerseq)]
    {:inputs i-lays
     :regions r-lays}))

(defn rebuild-layouts
  "Used when the model remains the same but the display has
  changed. Maintains any sorting and facets on each layer/input
  layout. I.e. replaces the GridLayout within each OrderableLayout."
  [viz-layouts model opts]
  (let [grid-layouts (init-grid-layouts model opts)]
    (->
     (reduce (fn [m path]
               (update-in m path
                          (fn [lay]
                            (assoc lay :layout (get-in grid-layouts path)))))
             viz-layouts
             (all-layout-paths viz-layouts))
     (reset-layout-caches))))

(defn init-layouts
  [model opts]
  (let [grid-layouts (init-grid-layouts model opts)]
    (->
     (reduce (fn [m path]
               (update-in m path
                          (fn [lay]
                            (lay/orderable-layout lay (p/size-of lay)))))
             grid-layouts
             (all-layout-paths grid-layouts))
     (reset-layout-caches))))

(defn update-dt-offsets!
  [viz-layouts selection opts]
  (swap! viz-layouts
         (fn [m]
           (let [sel-dt (:dt @selection)
                 draw-steps (get-in opts [:drawing :draw-steps])
                 dt0 (max 0 (- sel-dt (quot draw-steps 2)))]
             (-> (reduce (fn [m path]
                           (update-in m path assoc-in [:layout :dt-offset] dt0))
                         m
                         (all-layout-paths m))
                 (reset-layout-caches))))))

(defn scroll-sel-layer!
  [viz-layouts viz-options down? rgn-id lyr-id]
  (swap! viz-layouts
         update-in [:regions rgn-id lyr-id]
         (fn [lay]
           (lay/scroll lay down?)))
  ;; need this to invalidate the drawing cache
  (swap! viz-options update-in [:columns :refresh-index] inc))

(defn scroll-all-layers!
  [viz-layouts viz-options down?]
  (swap! viz-layouts
         (fn [m]
           (reduce (fn [m path]
                     (update-in m path lay/scroll down?))
                   m
                   (all-layout-paths m))))
  ;; need this to invalidate the drawing cache
  (swap! viz-options
         (fn [m]
           (-> m
               (update-in [:columns :refresh-index] inc)
               (update-in [:input :refresh-index] inc)))))

(defn active-bits
  [inp]
  (if (:encoder inp)
    (p/bits-value inp)
    (p/motor-bits-value inp)))

(defn add-facet-to-sel-layer!
  [viz-layouts viz-options htm rgn-id lyr-id]
  (swap! viz-layouts
         update-in [:regions rgn-id lyr-id]
         (fn [lay]
           (let [lyr (get-in htm [:regions rgn-id lyr-id])
                 ids (sort (p/active-columns lyr))]
             (lay/add-facet lay ids (p/timestep htm)))))
  ;; need this to invalidate the drawing cache
  (swap! viz-options update-in [:columns :refresh-index] inc))

(defn add-facet-to-all-layers!
  [viz-layouts viz-options htm]
  (swap! viz-layouts
         (fn [m]
           (reduce (fn [m path]
                     (update-in m path
                                (fn [lay]
                                  (let [[lyr-type _ _] path
                                        get-active (case lyr-type
                                                     :regions p/active-columns
                                                     :inputs active-bits)
                                        lyr (get-in htm path)
                                        ids (sort (get-active lyr))]
                                    (lay/add-facet lay ids (p/timestep htm))))))
                   m
                   (all-layout-paths m))))
  ;; need this to invalidate the drawing cache
  (swap! viz-options
         (fn [m]
           (-> m
               (update-in [:columns :refresh-index] inc)
               (update-in [:input :refresh-index] inc)))))

(defn clear-facets-on-sel-layer!
  [viz-layouts rgn-id lyr-id]
  (swap! viz-layouts
         update-in [:regions rgn-id lyr-id]
         lay/clear-facets))

(defn clear-facets-on-all-layers!
  [viz-layouts]
  (swap! viz-layouts
         (fn [m]
           (reduce (fn [m path]
                     (update-in m path lay/clear-facets))
                   m
                   (all-layout-paths m)))))

(defn sort-sel-layer!
  [viz-layouts viz-options model-steps sel-dt rgn-id lyr-id]
  (let [use-steps (max 2 (get-in @viz-options [:drawing :draw-steps]))
        model-steps (take use-steps (drop sel-dt model-steps))]
    (swap! viz-layouts
           update-in [:regions rgn-id lyr-id]
           (fn [lay]
             (let [ids-ts (for [htm model-steps]
                            (-> (get-in htm [:regions rgn-id lyr-id])
                                (p/active-columns)
                                (sort)))]
               (lay/sort-by-recent-activity lay ids-ts)))))
  ;; need this to invalidate the drawing cache
  (swap! viz-options update-in [:columns :refresh-index] inc))

(defn sort-all-layers!
  [viz-layouts viz-options model-steps sel-dt]
  (let [use-steps (max 2 (get-in @viz-options [:drawing :draw-steps]))
        model-steps (take use-steps (drop sel-dt model-steps))]
    (swap! viz-layouts
           (fn [m]
             (reduce (fn [m path]
                       (update-in m path
                                  (fn [lay]
                                    (let [[lyr-type _ _] path
                                          get-active (case lyr-type
                                                       :regions p/active-columns
                                                       :inputs active-bits)
                                          ids-ts (for [htm model-steps]
                                                   (-> (get-in htm path)
                                                       (get-active)
                                                       (sort)))]
                                      (lay/sort-by-recent-activity lay ids-ts)))))
                     m
                     (all-layout-paths m)))))
  ;; need this to invalidate the drawing cache
  (swap! viz-options
         (fn [m]
           (-> m
               (update-in [:columns :refresh-index] inc)
               (update-in [:input :refresh-index] inc)))))

(defn clear-sort-on-sel-layer!
  [viz-layouts viz-options rgn-id lyr-id]
  (swap! viz-layouts
         update-in [:regions rgn-id lyr-id]
         lay/clear-sort)
  ;; need this to invalidate the drawing cache
  (swap! viz-options update-in [:columns :refresh-index] inc))

(defn clear-sort-on-all-layers!
  [viz-layouts viz-options]
  (swap! viz-layouts
         (fn [m]
           (reduce (fn [m path]
                     (update-in m path lay/clear-sort))
                   m
                   (all-layout-paths m))))
  ;; need this to invalidate the drawing cache
  (swap! viz-options
         (fn [m]
           (-> m
               (update-in [:columns :refresh-index] inc)
               (update-in [:input :refresh-index] inc)))))

(defn draw-ff-synapses
  [ctx htm r-lays i-lays selection opts]
  (c/save ctx)
  (c/stroke-width ctx 1)
  (c/alpha ctx 1)
  (let [{dt :dt, sel-rgn :region, sel-lyr :layer, sel-col :col} selection
        do-growing? (get-in opts [:ff-synapses :growing])
        do-inactive? (get-in opts [:ff-synapses :inactive])
        do-disconn? (get-in opts [:ff-synapses :disconnected])
        do-perm? (get-in opts [:ff-synapses :permanences])
        syn-states (concat (when do-disconn? [:disconnected])
                           (when do-inactive? [:inactive-syn])
                           [:active :active-predicted]
                           (when do-growing? [:growing]))
        regions (:regions htm)
        ;; need to know which layers have input across regions
        input-layer? (into #{} (map (fn [[rgn-id rgn]]
                                      [rgn-id (first (core/layers rgn))])
                                    regions))
        ;; need to know the output layer of each region
        output-layer (into {} (map (fn [[rgn-id rgn]]
                                     [rgn-id (last (core/layers rgn))])
                                   regions))
        this-rgn (get regions sel-rgn)
        this-lyr (get this-rgn sel-lyr)
        to-cols (case (get-in opts [:ff-synapses :to])
                    :all (p/active-columns this-lyr)
                    :selected [sel-col])
        this-paths (map #(vector sel-rgn sel-lyr %) to-cols)]
    ;; trace ff connections downwards
    (loop [path (first this-paths)
           more (rest this-paths)
           done #{}]
      (if (and path (not (done path)))
        (let [[rgn-id lyr-id col] path
              lyr (get-in regions [rgn-id lyr-id])
              in-bits (:in-ff-bits (:state lyr))
              in-sbits (:in-stable-ff-bits (:state lyr))
              sg (:proximal-sg lyr)
              prox-learning (:proximal-learning (:state lyr))
              seg-up (get prox-learning [col 0])
              {learn-seg-path :target-id, grow-sources :grow-sources} seg-up
              this-seg-path (or learn-seg-path [col 0 0])
              all-syns (p/in-synapses sg this-seg-path)
              syns (select-keys all-syns (p/sources-connected-to sg this-seg-path))
              this-lay (get-in r-lays [rgn-id lyr-id])
              [this-x this-y] (element-xy this-lay col dt)]
          (recur
           (first more)
           (into (next more)
                 (for [syn-state syn-states
                       :let [sub-syns (case syn-state
                                        :active (select-keys syns in-bits)
                                        :active-predicted (select-keys syns in-sbits)
                                        :inactive-syn (if do-disconn?
                                                        (apply dissoc all-syns in-bits)
                                                        (apply dissoc syns in-bits))
                                        :disconnected (-> (apply dissoc all-syns (keys syns))
                                                          (select-keys in-bits))
                                        :growing (select-keys syns grow-sources))
                             _ (c/stroke-style ctx (state-colors syn-state))]
                       [i perm] sub-syns]
                   (let [[src-id src-lyr src-i]
                         (if (input-layer? [rgn-id lyr-id])
                           ;; input from another region
                           (let [[src-id src-i]
                                 (core/source-of-incoming-bit htm rgn-id i)]
                             [src-id (output-layer src-id) src-i])
                           ;; input from another layer in same region (hardcoded)
                           [rgn-id :layer-4 i])
                         src-lay (or (get i-lays src-id)
                                     (get-in r-lays [src-id src-lyr]))
                         src-col (if src-lyr
                                   (first (p/source-of-bit
                                           (get-in regions [src-id src-lyr])
                                           src-i))
                                   src-i)
                         [src-x src-y] (element-xy src-lay src-col dt)]
                     (doto ctx
                       (c/alpha (if do-perm? perm 1))
                       (c/begin-path)
                       (c/move-to (- this-x 1) this-y) ;; -1 avoid obscuring colour
                       (c/line-to (+ src-x 1) src-y)
                       (c/stroke))
                     (when src-lyr
                       ;; source is a cell not an input bit, so continue tracing
                       [src-id src-lyr src-col]))))
           (conj done path)))
        ;; go on to next
        (when (seq more)
          (recur (first more) (next more) done)))))
  (c/restore ctx)
  ctx)

(defn group-synapses
  [syns ac pcon]
  (group-by (fn [[id p]]
                   [(if (>= p pcon)
                      :connected :disconnected)
                    (if (ac id)
                      :active :inactive)])
                 syns))

(defn natural-curve
  [ctx x0 y0 x1 y1]
  (let [x-third (/ (- x1 x0) 3)]
    (c/bezier-curve-to ctx
                       (- x1 x-third) y0
                       (+ x0 x-third) y1
                       x1 y1)))

(defprotocol PCellsSegmentsLayout
  (seg-xy [this ci si])
  (cell-xy [this ci])
  (col-cell-line [this ctx ci])
  (cell-seg-line [this ctx ci si])
  (clicked-seg [this x y]))

(defn all-cell-segments
  [col depth distal-sg]
  (let [cell-ids (map vector (repeat col) (range depth))]
    (mapv (fn [cell-id]
            (->> (p/cell-segments distal-sg cell-id)
                 (reverse)
                 (drop-while empty?)
                 (reverse)))
          cell-ids)))

(defn cells-segments-layout
  [col segs-by-cell cols-lay dt cells-left opts]
  (let [nsegbycell (map count segs-by-cell)
        nsegbycell-pad (map (partial max 1) nsegbycell)
        nseg-pad (apply + nsegbycell-pad)
        d-opts (:drawing opts)
        segs-left (+ cells-left (:seg-h-space-px d-opts))
        col-d-px (:col-d-px d-opts)
        col-r-px (* col-d-px 0.5)
        cell-r-px (:cell-r-px d-opts)
        seg-h-px (:seg-h-px d-opts)
        seg-w-px (:seg-w-px d-opts)
        our-height (:height-px d-opts)
        our-top (+ (:top-px d-opts) cell-r-px)
        [col-x col-y] (element-xy cols-lay col dt)]
    (reify PCellsSegmentsLayout
      (seg-xy
        [_ ci si]
        (let [i-all (apply + si (take ci nsegbycell-pad))
              frac (/ i-all nseg-pad)]
          [segs-left
           (+ our-top (* frac our-height))]))
      (cell-xy
        [this ci]
        (let [[_ sy] (seg-xy this ci 0)]
          [cells-left sy]))
      (col-cell-line
        [this ctx ci]
        (let [[cell-x cell-y] (cell-xy this ci)]
          (doto ctx
            (c/begin-path)
            (c/move-to (+ col-x col-r-px 1) col-y) ;; avoid obscuring colour
            (natural-curve col-x col-y cell-x cell-y)
            (c/stroke))))
      (cell-seg-line
        [this ctx ci si]
        (let [[cell-x cell-y] (cell-xy this ci)
              [sx sy] (seg-xy this ci si)]
          (doto ctx
            (c/begin-path)
            (c/move-to sx sy)
            (c/line-to (+ cell-x cell-r-px) cell-y)
            (c/stroke))))
      (clicked-seg
        [this x y]
        (when (<= (- cells-left cell-r-px) x
                  (+ segs-left seg-w-px 5))
          (first (for [[ci nsegs] (map-indexed vector nsegbycell)
                       si (range nsegs)
                       :let [[_ seg-y] (seg-xy this ci si)]
                       :when (<= (- seg-y seg-h-px) y
                                 (+ seg-y seg-h-px 5))]
                   [ci si])))))))

(defn draw-cell-segments
  [ctx htm prev-htm r-lays i-lays selection opts cells-left
   current-cell-segments-layout]
  (c/save ctx)
  (let [{dt :dt, sel-rgn :region, sel-lyr :layer, col :col, sel-ci-si :cell-seg} selection
        regions (:regions htm)
        lyr (get-in regions [sel-rgn sel-lyr])
        lay (get-in r-lays [sel-rgn sel-lyr])
        spec (p/params lyr)
        stimulus-th (:seg-stimulus-threshold spec)
        learning-th (:seg-learn-threshold spec)
        pcon (:distal-perm-connected spec)
        pinit (:distal-perm-init spec)
        ac (p/active-cells lyr)
        prev-lyr (get-in prev-htm [:regions sel-rgn sel-lyr])
        prev-ac (:active-cells (:state prev-lyr))
        prev-pc (:pred-cells (:prior-distal-state lyr))
        prev-aci (:distal-bits (:prior-distal-state lyr))
        depth (p/layer-depth lyr)
        learning (:distal-learning (:state lyr))
        seg-up (first (vals (select-keys learning (for [ci (range depth)] [col ci]))))
        {[_ learn-ci learn-si] :target-id, grow-sources :grow-sources} seg-up
        segs-by-cell (->> (:distal-sg lyr)
                          (all-cell-segments col depth))
        p-segs-by-cell (when prev-htm
                         (->> (get-in prev-htm [:regions sel-rgn sel-lyr :distal-sg])
                              (all-cell-segments col depth)))
        cslay (cells-segments-layout col segs-by-cell lay dt cells-left opts)
        col-d-px (get-in opts [:drawing :col-d-px])
        cell-r-px (get-in opts [:drawing :cell-r-px])
        seg-h-px (get-in opts [:drawing :seg-h-px])
        seg-w-px (get-in opts [:drawing :seg-w-px])
        seg-r-px (* seg-w-px 0.5)]
    ;; for the click handler to use
    (reset! current-cell-segments-layout cslay)
    (doseq [[ci segs] (map-indexed vector segs-by-cell)
            :let [p-segs (nth p-segs-by-cell ci)
                  [cell-x cell-y] (cell-xy cslay ci)
                  cell-id [col ci]
                  cell-active? (ac cell-id)
                  cell-predictive? (get prev-pc cell-id)
                  cell-learning? (= ci learn-ci)
                  ;; need to add an entry for a new segment if just grown
                  use-segs (if (and cell-learning? (>= learn-si (count p-segs)))
                             (take (inc learn-si) (concat p-segs (repeat {})))
                             p-segs)
                  selected-cell? (if sel-ci-si
                                   (== ci (first sel-ci-si))
                                   cell-learning?)
                  cell-state (cond
                              (and cell-active? cell-predictive?) :active-predicted
                              cell-predictive? :predicted
                              cell-active? :active
                              :else :inactive)]]
      ;; draw background lines to cell from column and from segments
      (c/stroke-width ctx col-d-px)
      (c/stroke-style ctx (:background state-colors))
      (col-cell-line cslay ctx ci)
      (doseq [si (range (count segs))]
        (cell-seg-line cslay ctx ci si))
      (when cell-active?
        (doto ctx
          (c/stroke-style (:active state-colors))
          (c/stroke-width 2))
        (col-cell-line cslay ctx ci))
      ;; draw the cell itself
      (when selected-cell?
        (doto ctx
          (c/fill-style (:highlight state-colors))
          (c/circle {:x cell-x :y cell-y :r (+ cell-r-px 8)})
          (c/fill)))
      (doto ctx
        (c/fill-style (state-colors cell-state))
        (c/stroke-style "black")
        (c/stroke-width 1)
        (c/circle {:x cell-x :y cell-y :r cell-r-px})
        (c/stroke)
        (c/fill))
      (c/fill-style ctx "black")
      (c/text ctx {:text (str "cell " ci)
                   :x (+ cell-x 10) :y (- cell-y cell-r-px 5)})
      ;; draw each segment
      (doseq [[si seg] (map-indexed vector use-segs)
              :let [[sx sy] (seg-xy cslay ci si)
                    grouped-syns (group-synapses seg prev-aci pcon)
                    conn-act (count (grouped-syns [:connected :active]))
                    conn-tot (+ (count (grouped-syns [:connected :inactive]))
                                conn-act)
                    disc-act (count (grouped-syns [:disconnected :active]))
                    disc-tot (+ (count (grouped-syns [:disconnected :inactive]))
                                disc-act)
                    z (-> (/ conn-act stimulus-th)
                          (min 1.0))
                    learn-seg? (and cell-learning? (= si learn-si))
                    selected-seg? (if sel-ci-si
                                    (= [ci si] sel-ci-si)
                                    learn-seg?)
                    scale (/ seg-w-px stimulus-th)]]
        ;; draw segment as a rectangle
        (let [h2 (int (/ seg-h-px 2))
              conn-th-r {:x sx :y (- sy h2) :w (int (* stimulus-th scale)) :h seg-h-px}
              conn-tot-r (assoc conn-th-r :w (* conn-tot scale))
              conn-act-r (assoc conn-th-r :w (* conn-act scale))
              disc-th-r {:x sx :y (+ sy h2) :w (int (* learning-th scale)) :h seg-h-px}
              disc-tot-r (assoc disc-th-r :w (* disc-tot scale))
              disc-act-r (assoc disc-th-r :w (* disc-act scale))]
          (when selected-seg?
            (doto ctx
              (c/fill-style (:highlight state-colors))
              (c/fill-rect {:x (- sx 5) :y (- sy h2 5) :w (+ seg-w-px 5 5) :h (+ (* 2 seg-h-px) 5 5)})))
          (doto ctx
            (c/fill-style "white") ;; overlay on highlight rect
            (c/fill-rect conn-th-r)
            (c/fill-rect disc-th-r)
            (c/fill-style (:background state-colors))
            (c/fill-rect conn-tot-r)
            (c/fill-rect disc-tot-r)
            (c/stroke-style "black")
            (c/stroke-width 1)
            (c/fill-style (:active state-colors))
            (c/fill-rect conn-act-r)
            (c/stroke-rect conn-th-r)
            (c/alpha 0.5)
            (c/fill-rect disc-act-r)
            (c/stroke-rect disc-th-r)
            (c/alpha 1.0)))
        (when (>= conn-act stimulus-th)
          (doto ctx
            (c/stroke-style (:active state-colors))
            (c/stroke-width 2))
          (cell-seg-line cslay ctx ci si))
        (c/fill-style ctx "black")
        (c/text-align ctx :right)
        (c/text ctx {:text (str "seg " si "") :x (- sx 3) :y sy})
        (c/text-align ctx :start)
        (when learn-seg?
          (c/text ctx {:text (str "learning") :x (+ sx seg-w-px 10) :y sy}))
        ;; draw distal synapses
        (c/stroke-width ctx 1)
        (let [do-from (get-in opts [:distal-synapses :from])
              do-growing? (get-in opts [:distal-synapses :growing])
              do-inactive? (get-in opts [:distal-synapses :inactive])
              do-disconn? (get-in opts [:distal-synapses :disconnected])
              do-perm? (get-in opts [:distal-synapses :permanences])
              syn-states (concat (when do-disconn? [:disconnected])
                                 (when do-inactive? [:inactive-syn])
                                 [:active]
                                 (when do-growing? [:growing]))
              grouped-sourced-syns
              (util/remap (fn [syns]
                            (map (fn [[i p]]
                                   [i
                                    (core/source-of-distal-bit htm sel-rgn sel-lyr i)
                                    p])
                                 syns))
                          (assoc grouped-syns
                                 :growing (if learn-seg? (map vector grow-sources (repeat pinit)))))]
          (when (or (= do-from :all)
                    (and (= do-from :selected) selected-seg?))
            (doseq [syn-state syn-states
                    :let [source-info (case syn-state
                                        :active (grouped-sourced-syns [:connected :active])
                                        :inactive-syn (concat (grouped-sourced-syns [:connected :inactive])
                                                              (if do-disconn?
                                                                (grouped-sourced-syns [:disconnected :inactive])))
                                        :disconnected (grouped-sourced-syns [:disconnected :active])
                                        :growing (grouped-sourced-syns :growing))
                          _ (c/stroke-style ctx (state-colors syn-state))]]
              (c/stroke-style ctx (state-colors syn-state))
              (doseq [[i [src-id src-lyr src-i] p] source-info
                      :let [src-lay (or (get i-lays src-id)
                                        (get-in r-lays [src-id src-lyr]))
                            src-col (if src-lyr
                                      (first (p/source-of-bit
                                              (get-in regions [src-id src-lyr])
                                              src-i))
                                      src-i)
                            [src-x src-y] (element-xy src-lay src-col (inc dt))]]
                (when do-perm? (c/alpha ctx p))
                (doto ctx
                  (c/begin-path)
                  (c/move-to sx sy)
                  (c/line-to (+ src-x 1) src-y) ;; +1 avoid obscuring colour
                  (c/stroke))))
            (c/alpha ctx 1.0)))))
    (c/restore ctx))
  ctx)

(defn image-buffer
  [{:keys [w h]}]
  (let [el (dom/createElement "canvas")]
    (set! (.-width el) w)
    (set! (.-height el) h)
    el))

(defn bg-image
  [lay]
  (let [el (image-buffer (layout-bounds lay))
        ctx (c/get-context el "2d")]
    (c/fill-style ctx (:background state-colors))
    (fill-elements lay ctx (lay/ids-onscreen lay))
    el))

(defn active-bits-image
  [lay inp]
  (let [el (image-buffer (layout-bounds lay))
        ctx (c/get-context el "2d")
        inbits (active-bits inp)]
    (c/fill-style ctx (:active state-colors))
    (fill-elements lay ctx inbits)
    el))

(defn pred-bits-image
  [lay prev-rgn]
  (let [el (image-buffer (layout-bounds lay))
        ctx (c/get-context el "2d")
        bit-votes (core/predicted-bit-votes prev-rgn)
        bit-alpha (util/remap #(min 1.0 (/ % 8)) bit-votes)]
    (c/fill-style ctx (:predicted state-colors))
    (group-and-fill-elements lay ctx bit-alpha c/alpha)
    el))

(defn active-columns-image
  [lay lyr]
  (let [el (image-buffer (layout-bounds lay))
        ctx (c/get-context el "2d")
        cols (p/active-columns lyr)]
    (c/fill-style ctx (:active state-colors))
    (fill-elements lay ctx cols)
    el))

(defn pred-columns-image
  [lay lyr]
  (let [el (image-buffer (layout-bounds lay))
        ctx (c/get-context el "2d")
        cols (->> (p/prior-predictive-cells lyr)
                  (map first)
                  (distinct))]
    (c/fill-style ctx (:predicted state-colors))
    (fill-elements lay ctx cols)
    ;; also draw breaks - these interrupt prediction & TP
    (when (empty? (:distal-bits (:prior-distal-state lyr)))
      (doto ctx
        (c/stroke-style "black")
        (c/stroke-width 2)
        (c/begin-path)
        (c/move-to 0.5 0)
        (c/line-to 0.5 (.-height el))
        (c/stroke)
        (c/stroke-width 1)))
    el))

(defn tp-columns-image
  [lay lyr]
  (let [el (image-buffer (layout-bounds lay))
        ctx (c/get-context el "2d")
        cols (->> (p/temporal-pooling-cells lyr)
                  (map first))]
    (c/fill-style ctx (:temporal-pooling state-colors))
    (fill-elements lay ctx cols)
    el))

(defn overlaps-columns-image
  [lay lyr]
  (let [el (image-buffer (layout-bounds lay))
        ctx (c/get-context el "2d")
        col-m (->> (:col-overlaps (:state lyr))
                   (reduce-kv (fn [m [col _ _] v]
                                (assoc! m col (max v (get m col 0))))
                              (transient {}))
                   (persistent!)
                   (util/remap #(min 1.0 (/ % 16))))]
    (c/fill-style ctx "black")
    (group-and-fill-elements lay ctx col-m c/alpha)
    el))

(defn boost-columns-image
  [lay lyr]
  (let [el (image-buffer (layout-bounds lay))
        ctx (c/get-context el "2d")
        spec (p/params lyr)
        maxb (:max-boost spec)
        col-m (->> (:boosts lyr)
                   (map #(/ (dec %) (dec maxb)))
                   (zipmap (range)))]
    (c/fill-style ctx "black")
    (group-and-fill-elements lay ctx col-m c/alpha)
    el))

(defn active-freq-columns-image
  [lay lyr]
  (let [el (image-buffer (layout-bounds lay))
        ctx (c/get-context el "2d")
        spec (p/params lyr)
        col-m (->> (:active-duty-cycles lyr)
                   (map #(min 1.0 (* 2 %)))
                   (zipmap (range)))]
    (c/fill-style ctx "black")
    (group-and-fill-elements lay ctx col-m c/alpha)
    el))

(defn count-segs-in-column
  [distal-sg depth col]
  (reduce (fn [n ci]
            (+ n (util/count-filter seq
                                    (p/cell-segments distal-sg [col ci]))))
          0
          (range depth)))

(defn n-segments-columns-image
  [lay lyr]
  (let [el (image-buffer (layout-bounds lay))
        ctx (c/get-context el "2d")
        sg (:distal-sg lyr)
        n-cols (p/size-of lyr)
        depth (p/layer-depth lyr)
        cols (lay/ids-onscreen lay)
        col-m (->> cols
                   (map #(count-segs-in-column sg depth %))
                   (map #(min 1.0 (/ % 16.0)))
                   (zipmap cols))]
    (c/fill-style ctx "black")
    (group-and-fill-elements lay ctx col-m c/alpha)
    el))

(defn scroll-status-str
  [lay inbits?]
  (let [idx (lay/scroll-position lay)
        page-n (count (lay/ids-onscreen lay))
        n-ids (p/size-of lay)]
    (str page-n
         " of "
         n-ids
         (if inbits? " bits" " cols")
         (if (pos? idx)
           (str " @ "
                (long (* 100 (/ idx (- n-ids page-n))))
                "%")
           ""))))

(defn should-draw? [steps opts]
  (let [{:keys [anim-go? anim-every height-px]} (:drawing opts)
        model (first steps)]
    (when (and anim-go? model height-px)
      (let [t (p/timestep model)]
        (zero? (mod t anim-every))))))

(defn draw-timeline!
  [ctx steps sel-dt opts]
  (let [current-t (p/timestep (first steps))
        keep-steps (:keep-steps opts)
        width-px (.-width (.-canvas ctx))
        height-px (.-height (.-canvas ctx))
        t-width (/ width-px keep-steps)
        y-px (/ height-px 2)
        r-px (min y-px (* t-width 0.5))
        sel-r-px y-px]
    (c/clear-rect ctx {:x 0 :y 0 :w width-px :h height-px})
    (c/text-align ctx :center)
    (c/text-baseline ctx :middle)
    (c/font-style ctx "bold 10px sans-serif")
    (doseq [dt (reverse (range keep-steps))
            :let [t (- current-t dt)
                  kept? (< dt (count steps))
                  x-px (- (dec width-px) r-px (* dt t-width))]]
      (c/fill-style ctx "black")
      (c/alpha ctx (cond (== dt sel-dt) 1.0 kept? 0.3 :else 0.1))
      (c/circle ctx {:x x-px :y y-px :r (if (== dt sel-dt) sel-r-px r-px)})
      (c/fill ctx)
      (when (or (== dt sel-dt)
                (and kept? (< keep-steps 100)))
        (c/fill-style ctx "white")
        (c/text ctx {:x x-px :y y-px :text (str t)})))
    (c/alpha ctx 1.0)))

(defn timeline-click
  [e steps selection opts]
  (let [{:keys [x y]} (offset-from-target e)
        keep-steps (:keep-steps opts)
        width-px (.-width (.-target e))
        t-width (/ width-px keep-steps)
        click-dt (quot (- (dec width-px) x) t-width)]
    (when (< click-dt (count steps))
      (swap! selection assoc :dt click-dt))))

(defn viz-timeline [model-steps selection viz-options]
  [resizing-canvas
   {:on-click #(timeline-click % @model-steps selection @viz-options)
    :style {:width "100%"
            :height "2em"}}
   [model-steps selection viz-options]
   (fn [ctx]
     (let [steps @model-steps
           opts @viz-options]
       (when (should-draw? steps opts)
         (draw-timeline! ctx steps (:dt @selection) opts))))
   nil])

(defn draw-viz!
  [ctx steps layouts sel opts current-cell-segments-layout]
  (let [{sel-dt :dt
         sel-rgn :region
         sel-lyr :layer
         sel-col :col} sel
        i-lays (:inputs layouts)
        r-lays (:regions layouts)
        d-opts (:drawing opts)
        draw-steps (case (:display-mode d-opts)
                     :one-d (:draw-steps d-opts)
                     :two-d 1)
        draw-dts (if (== 1 draw-steps)
                   [sel-dt]
                   ;; in case scrolled back in history
                   (let [dt0 (max 0 (- sel-dt (quot draw-steps 2)))]
                     (range dt0 (min (+ dt0 draw-steps)
                                     (count steps)))))
        sel-htm (nth steps sel-dt)
        sel-prev-htm (nth steps (inc sel-dt) nil)
        cells-left (->> (mapcat vals (vals r-lays))
                        (map lay/right-px)
                        (apply max)
                        (+ (:seg-h-space-px d-opts)))
        label-top-px 0
        width-px (.-width (.-canvas ctx))
        height-px (.-height (.-canvas ctx))]
    (c/clear-rect ctx {:x 0 :y 0 :w width-px :h height-px})
    ;; draw labels
    (c/text-align ctx :start)
    (c/text-baseline ctx :top)
    (c/font-style ctx "10px sans-serif")
    (c/fill-style ctx "black")
    (doseq [[inp-id lay] i-lays]
      (c/text ctx {:text (name inp-id)
                   :x (:x (layout-bounds lay))
                   :y label-top-px})
      (c/text ctx {:text (scroll-status-str lay true)
                   :x (:x (layout-bounds lay))
                   :y (+ label-top-px 10)}))
    (doseq [[rgn-id lyr-lays] r-lays
            [lyr-id lay] lyr-lays]
      (c/text ctx {:text (str (name rgn-id) " " (name lyr-id))
                   :x (:x (layout-bounds lay))
                   :y label-top-px})
      (c/text ctx {:text (scroll-status-str lay false)
                   :x (:x (layout-bounds lay))
                   :y (+ label-top-px 10)}))
    (c/text ctx {:text "Cells and distal dendrite segments."
                 :x cells-left :y label-top-px})
    (doseq [dt draw-dts
            :let [htm (nth steps dt)
                  prev-htm (nth steps (inc dt) nil)
                  dt-cache (::cache (meta htm))]]
      ;; draw encoded inbits
      (doseq [[inp-id lay] i-lays
              :let [inp (get-in htm [:inputs inp-id])
                    ;; region this input feeds to, for predictions
                    ff-rgn-id (first (get-in htm [:fb-deps inp-id]))
                    ;; TODO offset if multiple inputs feeding to region
                    prev-ff-rgn (when (pos? (p/size (p/ff-topology inp)))
                                  (get-in prev-htm [:regions ff-rgn-id]))
                    lay-cache (::cache (meta lay))]]
        (->> (bg-image lay)
             (with-cache lay-cache [::bg inp-id] opts #{:drawing})
             (draw-image-dt ctx lay dt))
        (when (get-in opts [:input :active])
          (->> (active-bits-image lay inp)
               (with-cache dt-cache [::abits inp-id] opts #{:input :drawing})
               (draw-image-dt ctx lay dt)))
        (when (and (get-in opts [:input :predicted])
                   prev-ff-rgn)
          (->> (pred-bits-image lay prev-ff-rgn)
               (with-cache dt-cache [::pbits inp-id] opts #{:input :drawing})
               (draw-image-dt ctx lay dt))))
      ;; draw regions / layers
      (doseq [[rgn-id lyr-lays] r-lays
              [lyr-id lay] lyr-lays
              :let [lyr (get-in htm [:regions rgn-id lyr-id])
                    uniqix (str (name rgn-id) (name lyr-id))
                    lay-cache (::cache (meta lay))]]
        (->> (bg-image lay)
             (with-cache lay-cache [::bg uniqix] opts #{:drawing})
             (draw-image-dt ctx lay dt))
        (when (get-in opts [:columns :overlaps])
          (->> (overlaps-columns-image lay lyr)
               (with-cache dt-cache [::ocols uniqix] opts #{:columns :drawing})
               (draw-image-dt ctx lay dt)))
        (when (get-in opts [:columns :boosts])
          (->> (boost-columns-image lay lyr)
               (with-cache dt-cache [::boosts uniqix] opts #{:columns :drawing})
               (draw-image-dt ctx lay dt)))
        (when (get-in opts [:columns :active-freq])
          (->> (active-freq-columns-image lay lyr)
               (with-cache dt-cache [::afreq uniqix] opts #{:columns :drawing})
               (draw-image-dt ctx lay dt)))
        (when (get-in opts [:columns :n-segments])
          (->> (n-segments-columns-image lay lyr)
               (with-cache dt-cache [::nsegcols uniqix] opts #{:columns :drawing})
               (draw-image-dt ctx lay dt)))
        (when (get-in opts [:columns :active])
          (->> (active-columns-image lay lyr)
               (with-cache dt-cache [::acols uniqix] opts #{:columns :drawing})
               (draw-image-dt ctx lay dt)))
        (when (get-in opts [:columns :predictive])
          (->> (pred-columns-image lay lyr)
               (with-cache dt-cache [::pcols uniqix] opts #{:columns :drawing})
               (draw-image-dt ctx lay dt)))
        (when (get-in opts [:columns :temporal-pooling])
          (->> (tp-columns-image lay lyr)
               (with-cache dt-cache [::tpcols uniqix] opts #{:columns :drawing})
               (draw-image-dt ctx lay dt)))))
    ;; mark facets
    (doseq [lay (vals i-lays)]
      (lay/draw-facets lay ctx))
    (doseq [lay (mapcat vals (vals r-lays))]
      (lay/draw-facets lay ctx))
    ;; highlight selection
    (when-let [lay (get-in r-lays [sel-rgn sel-lyr])]
      (lay/highlight-layer lay ctx (:highlight state-colors)))
    (when (> draw-steps 1)
      (doseq [lay (vals i-lays)]
        (lay/highlight-dt lay ctx sel-dt (:highlight state-colors)))
      (doseq [lay (mapcat vals (vals r-lays))]
        (lay/highlight-dt lay ctx sel-dt (:highlight state-colors))))
    (when sel-col
      (let [lay (get-in r-lays [sel-rgn sel-lyr])]
        (lay/highlight-element lay ctx sel-dt sel-col sel-col (:highlight state-colors))))
    ;; draw ff synapses
    (let [to (get-in opts [:ff-synapses :to])]
      (when (or (= to :all)
                (and (= to :selected)
                     sel-col))
        (draw-ff-synapses ctx sel-htm r-lays i-lays sel opts)))
    ;; draw selected cells and segments
    (when sel-col
      (draw-cell-segments ctx sel-htm sel-prev-htm r-lays i-lays sel opts
                          cells-left current-cell-segments-layout)))
  nil)

(def code-key
  {32 :space
   33 :page-up
   34 :page-down
   37 :left
   38 :up
   39 :right
   40 :down})

(def key->control-k
  {:left :step-backward
   :right :step-forward
   :up :column-up
   :down :column-down
   :page-up :scroll-up
   :page-down :scroll-down
   :space :toggle-run})

(defn viz-key-down
  [e commands-in]
  (if-let [k (code-key (.-keyCode e))]
    (do
      (put! commands-in [(key->control-k k)])
      (.preventDefault e))
    true))

(defn viz-click
  [e steps selection layouts current-cell-segments-layout]
  (let [{:keys [x y]} (offset-from-target e)
        i-lays (:inputs layouts)
        r-lays (:regions layouts)
        ;; we need to assume there is a previous step, so:
        max-dt (max 0 (- (count steps) 2))
        hit? (atom false)]
    ;; check inputs
    (doseq [[k lay] i-lays
            :let [[dt id] (lay/clicked-id lay x y)]
            :when dt]
      (reset! hit? true)
      (when (== 1 (count (p/dims-of lay)))
        (swap! selection assoc :dt (min dt max-dt))))
    ;; check regions
    (doseq [[rgn-id lyr-lays] r-lays
            [lyr-id lay] lyr-lays
            :let [[dt col] (lay/clicked-id lay x y)]
            :when dt]
      (reset! hit? true)
      (if (== 1 (count (p/dims-of lay)))
        (swap! selection assoc :region rgn-id :layer lyr-id :col col :cell-seg nil
               :dt (min dt max-dt))
        (swap! selection assoc :region rgn-id :layer lyr-id :col col :cell-seg nil)))
    ;; check cells
    (when (:col @selection)
      (when-let [cslay @current-cell-segments-layout]
        (when-let [[ci si] (clicked-seg cslay x y)]
          (reset! hit? true)
          (swap! selection assoc :cell-seg [ci si]))))
    (when-not @hit?
      ;; checked all, nothing clicked
      (swap! selection assoc :col nil :cell-seg nil))))

(defn absorb-changed-model [htm selection viz-options viz-layouts]
  (let [region-key (first (core/region-keys htm))
        layer-id (-> htm
                     (get-in [:regions region-key])
                     core/layers
                     first)]
    (reset! viz-layouts
            (init-layouts htm @viz-options))
    (swap! selection assoc
           :region region-key
           :layer layer-id
           :dt 0
           :col nil)))

(defn viz-canvas [_ model-steps selection viz-options commands-in-mult commands-out]
  (let [viz-layouts (atom nil)
        current-cell-segments-layout (clojure.core/atom nil)
        resizes (chan)

        ;; Use a mult for commands-in to avoid scenarios where there's a race
        ;; between two components, mounting and unmounting, competing for values
        ;; on the channel.
        commands-in (tap-c commands-in-mult)]

    (add-watch viz-options :rebuild-layouts
               (fn [_ _ old-opts opts]
                 (when (not= (:drawing opts)
                             (:drawing old-opts))
                   (swap! viz-layouts rebuild-layouts (first @model-steps) opts))))

    (add-watch selection :update-dt-offsets
               (fn [_ _ _ _]
                 (update-dt-offsets! viz-layouts selection @viz-options)))

    (go-loop []
      (when-let [[command & xs] (<! commands-in)]
        (case command
          :sort (let [[apply-to-all?] xs
                      sel-dt (:dt @selection)
                      sel-rgn (:region @selection)
                      sel-lyr (:layer @selection)]
                  (if apply-to-all?
                    (sort-all-layers! viz-layouts viz-options @model-steps sel-dt)
                    (sort-sel-layer! viz-layouts viz-options @model-steps sel-dt sel-rgn sel-lyr)))
          :clear-sort (let [[apply-to-all?] xs
                            sel-rgn (:region @selection)
                            sel-lyr (:layer @selection)]
                        (if apply-to-all?
                          (clear-sort-on-all-layers! viz-layouts viz-options)
                          (clear-sort-on-sel-layer! viz-layouts viz-options sel-rgn sel-lyr)))
          :add-facet (let [[apply-to-all?] xs
                           sel-dt (:dt @selection)
                           sel-rgn (:region @selection)
                           sel-lyr (:layer @selection)
                           htm (nth @model-steps sel-dt)]
                       (if apply-to-all?
                         (add-facet-to-all-layers! viz-layouts viz-options htm)
                         (add-facet-to-sel-layer! viz-layouts viz-options htm sel-rgn sel-lyr)))
          :clear-facets (let [[apply-to-all?] xs
                              sel-rgn (:region @selection)
                              sel-lyr (:layer @selection)]
                          (if apply-to-all?
                            (clear-facets-on-all-layers! viz-layouts)
                            (clear-facets-on-sel-layer! viz-layouts sel-rgn sel-lyr)))
          :step-backward (let [;; we need to assume there is a previous step, so:
                               max-dt (max 0 (- (count @model-steps) 2))]
                           (swap! selection update-in [:dt]
                                  #(min (inc %) max-dt)))
          :step-forward (if (zero? (:dt @selection))
                          (when commands-out
                            (put! commands-out :sim-step))
                          (swap! selection update-in [:dt]
                                 #(max (dec %) 0)))
          :column-up (when-let [col (:col @selection)]
                       (let [sel-rgn (:region @selection)
                             sel-lyr (:layer @selection)
                             lay (get-in @viz-layouts [:regions sel-rgn sel-lyr])
                             order (:order lay)
                             idx (order col)]
                         (if (zero? idx)
                           (swap! selection assoc :col nil)
                           (let [next-idx (dec idx)
                                 next-col (key (first (subseq order >= next-idx <= next-idx)))]
                             (swap! selection assoc :col next-col)))))
          :column-down (let [sel-rgn (:region @selection)
                             sel-lyr (:layer @selection)
                             lay (get-in @viz-layouts [:regions sel-rgn sel-lyr])
                             order (:order lay)
                             idx (if-let [col (:col @selection)]
                                   (order col)
                                   -1) ;; start at zero (inc -1)
                             next-idx (inc idx)
                             next-col (key (first (subseq order >= next-idx <= next-idx)))]
                         (swap! selection assoc :col next-col))
          :scroll-down (let [[apply-to-all?] xs
                             sel-rgn (:region @selection)
                             sel-lyr (:layer @selection)]
                         (if apply-to-all?
                           (scroll-all-layers! viz-layouts viz-options true)
                           (scroll-sel-layer! viz-layouts viz-options true sel-rgn sel-lyr)))
          :scroll-up (let [[apply-to-all?] xs
                           sel-rgn (:region @selection)
                           sel-lyr (:layer @selection)]
                       (if apply-to-all?
                         (scroll-all-layers! viz-layouts viz-options false)
                         (scroll-sel-layer! viz-layouts viz-options false sel-rgn sel-lyr)))
          :toggle-run (when commands-out
                        (put! commands-out :toggle-run))
          :on-model-changed (if (not-empty @model-steps)
                              (absorb-changed-model (first @model-steps) selection
                                                    viz-options viz-layouts)
                              (add-watch model-steps :absorb-changed-model
                                         (fn [_ _ _ v]
                                           (when (not-empty v)
                                             (remove-watch model-steps
                                                           :absorb-changed-model)
                                             (absorb-changed-model (first v)
                                                                   selection
                                                                   viz-options
                                                                   viz-layouts))))))
        (recur)))

    (go-loop []
      (when-let [[width-px height-px] (<! resizes)]
        (swap! viz-options (fn [opts]
                             (-> opts
                                 (assoc-in [:drawing :height-px] height-px)
                                 (assoc-in [:drawing :width-px] width-px))))
        (recur)))

    (reagent/create-class
     {:component-will-unmount #(async/close! commands-in)
      :display-name "viz-canvas"
      :reagent-render (fn [props _ _ _ _ _]
                        [resizing-canvas
                         (assoc props
                                :on-click #(viz-click % @model-steps selection @viz-layouts
                                                      current-cell-segments-layout)
                                :on-key-down #(viz-key-down % commands-in)
                                :style {:width "100%"
                                        :height "100vh"})
                         [selection model-steps viz-layouts viz-options]
                         (fn [ctx]
                           (let [steps @model-steps
                                 opts @viz-options]
                             (when (should-draw? steps opts)
                               (draw-viz! ctx steps @viz-layouts @selection opts
                                          current-cell-segments-layout))))
                         resizes])})))

(defn init-caches
  [htm]
  (vary-meta htm assoc ::cache (atom {})))
