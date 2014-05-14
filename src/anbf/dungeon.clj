(ns anbf.dungeon
  (:require [clojure.tools.logging :as log]
            [clojure.string :as string]
            [anbf.frame :refer [colormap]]
            [anbf.monster :refer :all]
            [anbf.util :refer [->Position]]
            [anbf.delegator :refer :all]))

(defrecord Tile
  [position
   glyph
   color
   feature ; :rock :floor :wall :stairs-up :stairs-down :corridor :altar :water :trap :door-open :door-closed :sink :fountain :grave :throne :bars :tree :drawbridge :lava :ice :underwater
   seen
   walked
   searched ; no. of times searched TODO
   items ; [Items]
   monster
   engraving]
  anbf.bot.ITile)

(defn- initial-tile [x y]
  (Tile. (->Position x y) \space nil nil false nil 0 [] nil nil))

(defrecord Level
  [dlvl
   branch-id
   tiles]
  anbf.bot.ILevel)

(defmethod print-method Level [level w]
  (.write w (str "#anbf.dungeon.Level"
                 (assoc (.without level :tiles) :tiles "<trimmed>"))))

(defn print-tiles
  "Print map, with pred overlayed with X where pred is not true for the tile"
  ([level]
   (print-tiles (constantly true) level))
  ([pred level]
   (dorun (map (fn [row]
                 (dorun (map (fn [tile]
                               (print (if (pred tile)
                                        (:glyph tile)
                                        \X))) row))
                 (println))
               (:tiles level)))))

(defn- initial-tiles []
  (->> (for [y (range 22)
             x (range 80)]
         (initial-tile x (inc y)))
       (partition 80) (map vec) vec))

(defn new-level [dlvl branch-id]
  (Level. dlvl branch-id (initial-tiles)))

(defn at
  "Tile at given position on the terminal"
  ([level pos]
   (at level (:x pos) (:y pos)))
  ([level x y]
   {:pre [(<= 1 y 22) (<= 0 x 79)]}
   (-> level :tiles (nth (dec y)) (nth x))))

(defn- new-branch-id []
  (-> "branch#" gensym keyword))

(def branches [:main :mines :sokoban :quest :ludios
               :vlad :earth :fire :air :water :astral])

(def branch-entry {:mines :main, :sokoban :main}) ; TODO

(defn dlvl-number [dlvl]
  (if-let [n (first (re-seq #"\d+" dlvl))]
    (Integer/parseInt n)))

(defn change-dlvl
  "Apply function to dlvl number if there is one, otherwise no change.  The result dlvl may not actually exist."
  [f dlvl]
  (if-let [n (dlvl-number dlvl)]
    (string/replace dlvl #"\d+" (str (f n)))
    dlvl))

(def upwards-branches [:sokoban :vlad])

(defn upwards? [branch] (some #(= branch %) upwards-branches))

(defn prev-dlvl
  "Dlvl closer to branch entry (for dlvl within the branch), no change for unnumbered dlvls."
  [branch dlvl]
  (if (upwards? branch)
    (change-dlvl inc dlvl)
    (change-dlvl dec dlvl)))

(defn next-dlvl
  "Dlvl further from branch entry (for dlvl within the branch), no change for unnumbered dlvls."
  [branch dlvl]
  (if (upwards? branch)
    (change-dlvl dec dlvl)
    (change-dlvl inc dlvl)))

(defn dlvl-compare
  "Only makes sense for dlvls within one branch."
  ([branch d1 d2]
   (if (upwards? branch)
     (dlvl-compare d2 d1)
     (dlvl-compare d1 d2)))
  ([d1 d2]
   (if (every? #(.contains % ":") [d1 d2])
     (compare (dlvl-number d1) (dlvl-number d2))
     (compare d1 d2))))

; branch ID is either a branch keyword from branches or random keyword that will map (via id->branch) to a standard branch keyword when the level branch is recognized.
; a Level should be permanently uniquely identified by its branch-id + dlvl.
(defrecord Dungeon
  [levels ; {:branch-id => sorted{"dlvl" => Level}}, recognized branches merged
   id->branch ; {:branch-id => :branch}, only ids of recognized levels included
   branch-start ; {:branch => dlvl of entrypoint}, only for recognized branches
   branch-id ; current
   dlvl] ; current
  anbf.bot.IDungeon)

(defn infer-branch [dungeon]
  dungeon) ; TODO assoc id->branch, merge id to branch in levels

; TODO if level looks visited find it by dlvl instead of adding
(defn add-level [dungeon {:keys [branch-id] :as level}]
  (assoc-in
    dungeon [:levels branch-id]
    (assoc (get (:levels dungeon) branch-id
                (sorted-map-by (partial dlvl-compare branch-id)))
           (:dlvl level) level)))

(defn new-dungeon []
  (add-level (Dungeon. {} (reduce #(assoc %1 %2 %2) (hash-map) branches)
                       {:earth "Dlvl:1"} :main "Dlvl:1")
             (new-level "Dlvl:1" :main)))

(defn monster? [glyph]
  (or (Character/isLetterOrDigit glyph)
      (#{\& \@ \' \; \:} glyph)))

(defn item? [glyph]
  (#{\" \) \[ \! \? \/ \= \+ \* \( \` \0 \$ \%} glyph))

(defn walkable? [tile]
  (some #(= % (:feature tile))
        [:ice :floor :air :altar :door-open :sink :fountain :corridor :trap
         :throne :grave :stairs-up :stairs-down]))

(defn boulder? [tile]
  (= (:glyph tile) \0))

(defn transparent?
  "For unexplored tiles just a guess"
  [{:keys [feature monster items] :as tile}]
  (and (not (boulder? tile))
       (not (#{:rock :wall :tree :door-closed :cloud} feature))
       (or feature monster items)))

(defn walkable-by [tile monster]
  ; TODO change feature: closed door to open door, rock/wall/tree to corridor.  consider xorns, slimes (door-ooze), fliers (over water/lava), fish
  tile)

(defn branch-key [{:keys [branch-id] :as dungeon}]
  (get (:id->branch dungeon) branch-id branch-id))

(defn curlvl [dungeon]
  (-> dungeon :levels (get (branch-key dungeon)) (get (:dlvl dungeon))))

(defn- infer-feature [tile new-glyph new-color]
  (case new-glyph
    \space (:feature tile) ; TODO :air
    \. (if (= (colormap new-color) :cyan) :ice :floor)
    \< :stairs-up
    \> :stairs-down
    \\ (if (= (colormap new-color) :yellow) :throne :grave)
    \{ (if (= (colormap new-color) nil) :sink :fountain)
    \} :TODO ; TODO :bars :tree :drawbridge :lava :underwater
    \# :corridor ; TODO :cloud
    \_ :altar
    \~ :water
    \^ :trap
    \] :door-closed
    \| (if (= (colormap new-color) :brown) :door-open :wall)
    \- (if (= (colormap new-color) :brown) :door-open :wall)
    (log/error "unrecognized appearance of tile" tile)))

(defn- update-feature [tile new-glyph new-color]
  ; TODO events
  (cond (monster? new-glyph) (walkable-by tile (:monster tile))
        (item? new-glyph) tile
        :else (update-in tile [:feature]
                         infer-feature new-glyph new-color)))

(defn- update-monster [tile new-glyph new-color]
  ; TODO events (monster gone, monster appeared)
  (assoc tile :monster
         (if (and (monster? new-glyph))
           (monster new-glyph new-color)
           nil)))

; they might not have actually been seen but there's usually not much to see in walls
(defn- mark-wall-seen [tile]
  (if (= :wall (:feature tile))
    (assoc tile :seen true)
    tile))

; TODO handle properly, mark new unknown items, track disappeared items
(defn- update-items [tile new-glyph new-color]
  (if-let [item (item? new-glyph)]
    (assoc tile :items [item])
    (if (monster? new-glyph)
      tile
      (assoc tile :items nil))))

(defn- parse-tile [tile new-glyph new-color]
  (if (or (not= new-color (:color tile)) (not= new-glyph (:glyph tile)))
    (-> tile
        (update-monster new-glyph new-color)
        (update-items new-glyph new-color)
        (update-feature new-glyph new-color)
        (assoc :glyph new-glyph :color new-color)
        mark-wall-seen)
    tile))

(defn map-tiles
  "Call f on each tile (or each tuple of tiles if there are more args) in 21x80 vector structures to again produce 21x80 vector of vectors"
  [f & tile-colls]
  (->> tile-colls
       (map (partial apply concat))
       (apply (partial map f))
       (partition 80) (map vec) vec))

; TODO change branch-id on staircase ascend/descend event or special levelport (quest/ludios)
(defn update-dlvl [dungeon status delegator]
  (assoc dungeon :dlvl (:dlvl status)))

(defn update-dungeon [{:keys [dungeon] :as game} {:keys [cursor] :as frame}]
  (-> game
      ; parse the new tiles
      (update-in [:dungeon :levels (branch-key dungeon) (:dlvl dungeon) :tiles]
                 (partial map-tiles parse-tile)
                 (drop 1 (:lines frame)) (drop 1 (:colors frame)))
      ; mark player as friendly
      (assoc-in [:dungeon :levels (branch-key dungeon) (:dlvl dungeon) :tiles
                 (dec (:y cursor)) (:x cursor) :monster :friendly] true)
      (update-in [:dungeon] infer-branch)))