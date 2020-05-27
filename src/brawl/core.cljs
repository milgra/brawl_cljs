(ns ^:figwheel-hooks brawl.core
  (:require
   [cljs.core.async :refer [<! chan put! take! poll!]]
   [goog.dom :as dom]
   [goog.events :as events]
   [gui.math4 :as math4]
   [gui.webgl :as uiwebgl]
   [brawl.ui :as brawlui]
   [brawl.world :as world]
   [brawl.webgl :as webgl]
   [brawl.audio :as audio]
   [brawl.actor :as actor]
   [brawl.layouts :as layouts]
   [brawl.particle :as particle]
   [brawl.defaults :as defaults]
   [brawl.actorskin :as actorskin]
   [brawl.floatbuffer :as floatbuffer])
  (:import [goog.events EventType]))


(defn resize-context!
  "vresize canvas on window resize"
  []
  (dom/setProperties
   (dom/getElement "main")
   (clj->js {:width (.-innerWidth js/window)
             :height (.-innerHeight js/window)})))


(defn load-font!
  "load external font"
  [state name url]
  (let [font (js/FontFace. name (str "url(" url ")"))]
    (.then
     (.load font)
     (fn []
       (.add (.-fonts js/document) font)
       (put! (:msgch state) {:id "redraw-ui"})))))


(defn init-events!
  "start event listening"
  [state]
  (let [key-codes (atom {})
        mouse-down (atom false)]
    
    (events/listen
     js/document
     EventType.KEYDOWN
     (fn [event]
       (let [code (.-keyCode event)
             prev (get @key-codes code)]
         (swap! key-codes assoc code true)
         (if (not prev) (put! (:msgch state) {:id "key" :code (.-keyCode event) :value true})))))

    (events/listen
     js/document
     EventType.KEYUP
     (fn [event]
       (let [code (.-keyCode event)
             prev (get @key-codes code)]
         (swap! key-codes assoc code false)
         (if prev (put! (:msgch state) {:id "key" :code (.-keyCode event) :value false})))))

    (events/listen
     js/document
     EventType.MOUSEDOWN
     (fn [event]
       (swap! mouse-down not)
       (put! (:msgch state) {:id "mouse" :type "down" :point [(.-clientX event) (.-clientY event)]})))

    (events/listen
     js/document
     EventType.MOUSEUP
     (fn [event]
       (swap! mouse-down not)
       (put! (:msgch state) {:id "mouse" :type "up" :point [(.-clientX event) (.-clientY event)]})))

    (events/listen
     js/document
     EventType.MOUSEMOVE
     (fn [event]
       (if @mouse-down (put! (:msgch state) {:id "mouse" :type "down" :point [(.-clientX event) (.-clientY event)]}))))

    (events/listen
     js/document
     EventType.TOUCHSTART
     (fn [event] nil))

    (events/listen
     js/window
     EventType.RESIZE
     (fn [event]
       (put! (:msgch state) {:id "resize"})
       (resize-context!)))))


(defn draw-ui
  "draw ui elements with ui-drawer"
  [{{:keys [views viewids projection] :as ui} :ui ui-drawer :ui-drawer :as state}]
  (assoc state :ui-drawer (uiwebgl/draw! ui-drawer projection (map views viewids))))


(defn draw-world
  "draws background, actors, masses with projection"
  [{:keys [world-drawer buffer physics]
    {:keys [actors particles surfacelines view-rect projection] :as world} :world :as state} frame]
  (if-not (:inited world)
    state
    (let [variation (Math/floor (mod (/ frame 10.0) 3.0 ))
          buffer-triangle (-> buffer
                              (floatbuffer/empty!)
                              ((partial reduce (fn [oldbuf [id actor]] (actorskin/get-skin-triangles actor oldbuf variation view-rect))) actors))]
      ;; draw triangles
      (webgl/clear! world-drawer)
      (webgl/drawshapes! world-drawer projection variation)
      (webgl/drawtriangles! world-drawer projection buffer-triangle)
      
      (let [buffer-points (cond-> buffer-triangle
                              true (floatbuffer/empty!)
                              true ((partial reduce (fn [oldbuf particle] (particle/get-point particle oldbuf))) particles)
                              physics ((partial reduce (fn [oldbuf [id actor]] (actorskin/getpoints actor oldbuf view-rect))) actors))]
        
        ;; draw points
        (webgl/drawpoints! world-drawer projection buffer-points)
        
        (let [buffer-line (cond-> buffer-points
                              true (floatbuffer/empty!)
                              physics ((partial reduce (fn [oldbuf [id actor]] (actorskin/getlines actor oldbuf view-rect))) actors)
                              physics (floatbuffer/append! surfacelines))]
          ;; draw lines
          (if physics (webgl/drawlines! world-drawer projection buffer-line))

          (-> state
              (assoc :world-drawer world-drawer)
              (assoc-in [:world :view-rect] view-rect)
              (assoc :buffer buffer-line)))))))


(defn update-controls
  "set up control state based on keycodes"
  [{:keys [keycodes controls] :as state } msg]
  (let [new-codes (if-not (and msg (= (:id msg) "key"))
                    keycodes
                    (assoc keycodes (:code msg) (:value msg)))
        new-controls {:left (new-codes 37)
                      :right (new-codes 39)
                      :up (new-codes 38)
                      :down (new-codes 40)
                      :punch (new-codes 70)
                      :run (new-codes 32)
                      :kick (new-codes 83)
                      :block (new-codes 68)}]
    (-> state
        (assoc :keycodes new-codes)
        (assoc :controls new-controls))))


(defn animate
  "main runloop, syncs animation to display refresh rate"
  [state draw-fn]
  (letfn [(loop [prestate frame]
            (fn [time]
              (let [newstate (if (> time 0)
                               (draw-fn prestate frame time)
                               prestate)]
                (.requestAnimationFrame
                 js/window
                 (loop newstate (inc frame))))))]
    ((loop state 0) 0 )))


(defn main
  "entering point"
  []
  (let [state {:ui (brawlui/init)
               :world (world/init)
               :ui-drawer (uiwebgl/init)
               :world-drawer (webgl/init)
               :level 0
               :msgch (chan)
               :sounds (audio/sounds)
               :buffer (floatbuffer/create!)
               :metrics (actor/basemetrics-random)
               :volumes {:music 0.5 :effects 0.5}
               :physics false
               :keycodes {}
               :controls {}
               :commands-ui []
               :commands-world []}
        
        final (-> state
                  (defaults/load-defaults!)
                  (audio/set-effects-volume)
                  (audio/set-music-volume)
                  (brawlui/load-ui layouts/info))]

    (load-font! final "Ubuntu Bold" "css/Ubuntu-Bold.ttf")
    (init-events! final)
    (resize-context!)
    (world/load-level! final (:level final))

    (animate
     final
     (fn [prestate frame time]
       ;; frame skipping for development
       (if-not (= (mod frame 1) 0 )
         prestate
         (let [msg (poll! (:msgch prestate))]
           (-> prestate
               ;; get controls
               (update-controls msg)
               ;; world
               (world/execute-commands)
               (world/reset-world msg)
               (world/update-world msg)
               ;; ui
               (brawlui/execute-commands msg)
               (brawlui/update-ui msg)
               ;; drawing
               (draw-world frame)
               (draw-ui))))))))

;; start main once, avoid firing new runloops with new reloads
(defonce mainloop (main))
