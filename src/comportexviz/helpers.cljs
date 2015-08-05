(ns comportexviz.helpers
  (:require [goog.dom]
            [goog.dom.classes]
            [goog.style :as style]
            [goog.events :as events]
            [reagent.core :as reagent :refer [atom]]
            [org.nfrac.comportex.core :as core]
            [org.nfrac.comportex.protocols :as p]
            [org.nfrac.comportex.util :as util :refer [round]]
            [cljs.core.async :as async :refer [put! <!]])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(defn- loading-message-element []
  (goog.dom/getElement "loading-message"))

(defn- show [el]
  (goog.dom.classes/add el "show"))

(defn- hide [el]
  (goog.dom.classes/remove el "show"))

(defn with-ui-loading-message
  [f]
  (let [el (loading-message-element)]
     (show el)
     ;; need a timeout to allow redraw to show loading message
     (js/setTimeout (fn []
                      (try
                        (f)
                        (finally
                          (hide el))))
                    100)))

(defn ui-loading-message-until
  [finished-c]
  (let [el (loading-message-element)]
    (show el)
    (go (<! finished-c)
        (hide el))))

(defn text-world-input-component
  [in-value htm max-shown scroll-every separator]
  (let [time (p/timestep htm)
        show-n (- max-shown (mod (- max-shown time) scroll-every))
        history (->> (:history (meta in-value))
                     (take-last show-n))]
    [:p
     (for [[i word] (map-indexed vector history)
           :let [t (+ i (- time (dec (count history))))
                 curr? (== time t)]]
       ^{:key (str word t)}
       [(if curr? :ins :span) (str word separator)]
       )]
    ))

(defn predictions-table
  [predictions]
  [:div
   [:table.table
    [:tbody
     [:tr
      [:th "prediction"]
      [:th "votes %"]
      [:th "votes per bit"]]
     (for [[j {:keys [value votes-frac votes-per-bit]
               }] (map-indexed vector predictions)]
       (let [txt value]
         ^{:key (str txt j)}
         [:tr
          [:td txt]
          [:td.text-right (str (round (* votes-frac 100)))]
          [:td.text-right (str (round votes-per-bit))]]
         ))
     ]]])

(defn text-world-predictions-component
  [in-value htm n-predictions]
  (let [inp (first (core/input-seq htm))
        rgn (first (core/region-seq htm))
        pr-votes (core/predicted-bit-votes rgn)
        predictions (p/decode (:encoder inp) pr-votes n-predictions)]
    (predictions-table predictions)))

;;; canvas

(defn on-resize [component width-px height-px resizes]
  (let [el (reagent/dom-node component)
        size-px (style/getSize el)
        w (.-width size-px)
        h (.-height size-px)]
    (when (or (zero? w) (zero? h))
      ;; The "right" way around this is to not use display:none, and instead
      ;; simply exclude undisplayed nodes from the component's render fn.
      (js/console.warn "The resizing-canvas can't handle display:none."))
    (reset! width-px w)
    (reset! height-px h)
    (when resizes
      (put! resizes [w h]))))

(defn- canvas$call-draw-fn
  [component]
  ;; argv contains entire hiccup form, so it's shifted one to the right.
  (let [[_ _ _ _ _ draw] (reagent/argv component)]
    (draw (-> component
              reagent/dom-node
              (.getContext "2d")))))

(defn canvas [_ _ _ _ _]
  (reagent/create-class
   {:component-did-mount #(canvas$call-draw-fn %)

    :component-did-update #(canvas$call-draw-fn %)

    :display-name "canvas"
    :reagent-render (fn [props width height canaries _]
                      ;; Need to deref all atoms consumed by draw function to
                      ;; subscribe to changes.
                      (mapv deref canaries)
                      [:canvas (assoc props
                                      :width width
                                      :height height)])}))

(defn- resizing-canvas$call-draw-fn
  [component]
  ;; argv contains entire hiccup form, so it's shifted one to the right.
  (let [[_ _ _ draw _] (reagent/argv component)]
    (draw (-> component
              reagent/dom-node
              (.getContext "2d")))))

(defn resizing-canvas [_ _ _ resizes]
  (let [resize-key (atom nil)
        width-px (atom nil)
        height-px (atom nil)]
    (reagent/create-class
     {:component-did-mount (fn [component]
                             (reset! resize-key
                                     (events/listen js/window "resize"
                                                    #(on-resize component
                                                                width-px
                                                                height-px
                                                                resizes)))

                             ;; Causes a render + did-update.
                             (on-resize component width-px height-px resizes))

      :component-did-update #(resizing-canvas$call-draw-fn %)

      :component-will-unmount #(when @resize-key
                                 (events/unlistenByKey @resize-key))

      :display-name "resizing-canvas"
      :reagent-render (fn [props canaries _ _]
                        ;; Need to deref all atoms consumed by draw function to
                        ;; subscribe to changes.
                        (mapv deref canaries)
                        [:canvas (assoc props
                                   :width @width-px
                                   :height @height-px)])})))
