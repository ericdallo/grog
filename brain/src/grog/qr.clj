(ns grog.qr
  "QR codes, for grog's screen to show the links it makes (`GET /v1/qr`):
  ISO/IEC 18004 in byte mode, error correction level M, versions 1 to 10
  (up to `max-bytes` of UTF-8). The mask is picked as libqrencode (the
  `qrencode` command) picks it, so that both draw the same code, which the
  package's tests check. `svg` draws one."
  (:require [clojure.string :as str]))

(def max-bytes
  "The most a code holds here, in bytes: version 10's, at level M."
  213)

;; Per version, at level M: the error correction codewords of each block,
;; and each block's data codewords.
(def ^:private blocks
  [nil
   [10 [16]]
   [16 [28]]
   [26 [44]]
   [18 [32 32]]
   [24 [43 43]]
   [16 [27 27 27 27]]
   [18 [31 31 31 31]]
   [22 [38 38 39 39]]
   [22 [36 36 36 37 37]]
   [26 [43 43 43 43 44]]])

;; Per version, where the centers of alignment patterns are, on both axes.
(def ^:private alignments
  [nil [] [6 18] [6 22] [6 26] [6 30] [6 34] [6 22 38] [6 24 42] [6 26 46] [6 28 50]])

(defn ^:private data-codewords [version]
  (reduce + (second (blocks version))))

(defn ^:private count-bits
  "How many bits say how many bytes there are, in `version`."
  [version]
  (if (< version 10) 8 16))

(defn ^:private capacity
  "How many bytes `version` holds."
  [version]
  (quot (- (* 8 (data-codewords version)) 4 (count-bits version)) 8))

(defn version-for
  "The smallest version holding `n` bytes, or nil."
  [n]
  (first (filter #(<= n (capacity %)) (range 1 11))))

(defn ^:private bits
  "The `n` lowest bits of `value`, highest first."
  [value n]
  (map #(bit-and 1 (bit-shift-right value %)) (range (dec n) -1 -1)))

(defn ^:private codewords
  "The data codewords holding `data` (bytes, 0 to 255) in `version`: byte
  mode, how many, the bytes, a terminator, then padding."
  [data version]
  (let [total (* 8 (data-codewords version))
        stream (vec (concat (bits 2r0100 4) (bits (count data) (count-bits version)) (mapcat #(bits % 8) data)))
        stream (into stream (repeat (min 4 (- total (count stream))) 0))
        stream (into stream (repeat (mod (- (count stream)) 8) 0))]
    (vec (take (data-codewords version)
               (concat (map #(reduce (fn [acc b] (+ (* 2 acc) b)) 0 %) (partition 8 stream))
                       (cycle [0xec 0x11]))))))

(def ^:private exps
  "The powers of α in GF(256), modulo x^8 + x^4 + x^3 + x^2 + 1."
  (vec (take 255 (iterate #(let [x (bit-shift-left % 1)] (if (> x 255) (bit-xor x 0x11d) x)) 1))))

(def ^:private logs
  (reduce-kv #(assoc %1 %3 %2) (vec (repeat 256 0)) exps))

(defn ^:private gf* [a b]
  (if (or (zero? a) (zero? b))
    0
    (exps (mod (+ (logs a) (logs b)) 255))))

(defn ^:private divisor
  "The Reed-Solomon generator polynomial of degree `n`, without its leading
  1, highest degree first."
  [n]
  (subvec (reduce (fn [poly i]
                    (mapv bit-xor (conj poly 0) (into [0] (map #(gf* % (exps i))) poly)))
                  [1]
                  (range n))
          1))

(defn ^:private error-correction
  "The `n` error correction codewords of `data`."
  [data n]
  (let [d (divisor n)]
    (reduce (fn [remainder b]
              (let [factor (bit-xor b (first remainder))]
                (mapv #(bit-xor %1 (gf* %2 factor)) (conj (subvec remainder 1) 0) d)))
            (vec (repeat n 0))
            data)))

(defn ^:private interleaved
  "The codewords of `version` with `data` in them: the blocks' data, a
  codeword of each in turn, then their error correction, the same way."
  [data version]
  (let [[n sizes] (blocks version)
        split (first (reduce (fn [[out more] size] [(conj out (vec (take size more))) (drop size more)])
                             [[] data]
                             sizes))
        column (fn [vs]
                 (for [i (range (reduce max (map count vs)))
                       v vs
                       :when (< i (count v))]
                   (v i)))]
    (vec (concat (column split) (column (map #(error-correction % n) split))))))

(defn ^:private put
  "Module x, y of `m` as a function pattern: dark or not."
  [{:keys [size] :as m} x y dark]
  (let [i (+ x (* y size))]
    (-> m
        (assoc-in [:dark i] dark)
        (assoc-in [:fixed i] true))))

(defn ^:private finder
  "A finder pattern centered on `x`, `y`, with its separator."
  [m x y]
  (reduce (fn [m [dx dy]]
            (let [px (+ x dx)
                  py (+ y dy)]
              (if (and (< -1 px (:size m)) (< -1 py (:size m)))
                (put m px py (not (contains? #{2 4} (max (abs dx) (abs dy)))))
                m)))
          m
          (for [dy (range -4 5) dx (range -4 5)] [dx dy])))

(defn ^:private alignment
  "An alignment pattern centered on `x`, `y`."
  [m x y]
  (reduce (fn [m [dx dy]] (put m (+ x dx) (+ y dy) (not= 1 (max (abs dx) (abs dy)))))
          m
          (for [dy (range -2 3) dx (range -2 3)] [dx dy])))

(defn ^:private format-bits
  "The format information of level M with `mask`: 15 bits, BCH-coded and
  masked."
  [mask]
  (let [remainder (reduce (fn [r _] (bit-xor (bit-shift-left r 1) (* (bit-shift-right r 9) 0x537)))
                          mask
                          (range 10))]
    (bit-xor (bit-or (bit-shift-left mask 10) remainder) 0x5412)))

(defn ^:private bit? [value i]
  (= 1 (bit-and 1 (bit-shift-right value i))))

(defn ^:private draw-format
  "Both copies of the format information of `mask`, and the dark module."
  [{:keys [size] :as m} mask]
  (let [b (format-bits mask)
        places (concat (for [i (range 6)] [8 i i])
                       [[8 7 6] [8 8 7] [7 8 8]]
                       (for [i (range 9 15)] [(- 14 i) 8 i])
                       (for [i (range 8)] [(- size 1 i) 8 i])
                       (for [i (range 8 15)] [8 (+ (- size 15) i) i]))]
    (put (reduce (fn [m [x y i]] (put m x y (bit? b i))) m places) 8 (- size 8) true)))

(defn ^:private draw-version
  "Both copies of the version information, from version 7 on."
  [{:keys [size] :as m} version]
  (if (< version 7)
    m
    (let [remainder (reduce (fn [r _] (bit-xor (bit-shift-left r 1) (* (bit-shift-right r 11) 0x1f25)))
                            version
                            (range 12))
          b (bit-or (bit-shift-left version 12) remainder)]
      (reduce (fn [m i]
                (let [a (+ (- size 11) (mod i 3))
                      c (quot i 3)]
                  (-> m (put a c (bit? b i)) (put c a (bit? b i)))))
              m
              (range 18)))))

(defn ^:private function-patterns
  "A symbol of `version` with its function patterns only: {:size :dark
  :fixed}, :dark and :fixed by module, row by row."
  [version]
  (let [size (+ 17 (* 4 version))
        blank (vec (repeat (* size size) false))
        centers (alignments version)
        last-center (dec (count centers))
        corner? (fn [i j] (contains? #{[0 0] [0 last-center] [last-center 0]} [i j]))
        m (reduce (fn [m i] (-> m (put 6 i (even? i)) (put i 6 (even? i))))
                  {:size size :dark blank :fixed blank}
                  (range size))
        m (-> m (finder 3 3) (finder (- size 4) 3) (finder 3 (- size 4)))
        m (reduce (fn [m [i j]] (if (corner? i j) m (alignment m (centers i) (centers j))))
                  m
                  (for [i (range (count centers)) j (range (count centers))] [i j]))]
    (-> m (draw-format 0) (draw-version version))))

(defn ^:private place
  "`m` with `codewords` in its free modules, two columns at a time, up then
  down, from the right."
  [{:keys [size fixed] :as m} codewords]
  (let [cells (for [right (concat (range (dec size) 6 -2) [5 3 1])
                    vertical (range size)
                    j [0 1]
                    :let [x (- right j)
                          y (if (zero? (bit-and (inc right) 2)) (- size 1 vertical) vertical)
                          i (+ x (* y size))]
                    :when (not (fixed i))]
                i)]
    (assoc m :dark (reduce (fn [dark [i b]] (assoc dark i (= 1 b)))
                           (:dark m)
                           (map vector cells (mapcat #(bits % 8) codewords))))))

;; Where each mask flips modules.
(def ^:private masks
  [(fn [x y] (zero? (mod (+ x y) 2)))
   (fn [_ y] (zero? (mod y 2)))
   (fn [x _] (zero? (mod x 3)))
   (fn [x y] (zero? (mod (+ x y) 3)))
   (fn [x y] (zero? (mod (+ (quot x 3) (quot y 2)) 2)))
   (fn [x y] (zero? (+ (mod (* x y) 2) (mod (* x y) 3))))
   (fn [x y] (zero? (mod (+ (mod (* x y) 2) (mod (* x y) 3)) 2)))
   (fn [x y] (zero? (mod (+ (mod (+ x y) 2) (mod (* x y) 3)) 2)))])

(defn ^:private masked
  "`m` with `mask` over its data, and the format information saying so."
  [{:keys [size dark fixed] :as m} mask]
  (let [flip? (masks mask)]
    (draw-format (assoc m :dark (vec (for [y (range size)
                                           x (range size)
                                           :let [i (+ x (* y size))]]
                                       (if (and (not (fixed i)) (flip? x y)) (not (dark i)) (dark i)))))
                 mask)))

(defn ^:private runs
  "The runs of a line of modules, as libqrencode counts them: their
  lengths, light and dark in turn, the first light (-1 when the line starts
  dark)."
  [line]
  (let [lengths (reduce (fn [lengths [a b]]
                          (if (= a b)
                            (update lengths (dec (count lengths)) inc)
                            (conj lengths 1)))
                        [1]
                        (partition 2 1 line))]
    (if (first line) (into [-1] lengths) lengths)))

(defn ^:private line-penalty
  "The penalties of a line's `runs` (libqrencode's Mask_calcN1N3): runs of 5
  or more, and patterns like a finder's (1:1:3:1:1, dark first) with light
  around."
  [runs]
  (let [n (count runs)]
    (reduce (fn [penalty i]
              (let [r (runs i)
                    penalty (cond-> penalty (>= r 5) (+ 3 (- r 5)))
                    fact (quot r 3)]
                (if (and (odd? i)
                         (<= 3 i (- n 3))
                         (zero? (mod r 3))
                         (= fact (runs (- i 2)) (runs (dec i)) (runs (inc i)) (runs (+ i 2)))
                         (or (= i 3)
                             (>= (runs (- i 3)) (* 4 fact))
                             (>= (+ i 4) n)
                             (>= (runs (+ i 3)) (* 4 fact))))
                  (+ penalty 40)
                  penalty)))
            0
            (range n))))

(defn ^:private penalty
  "How bad a masked symbol is to read, as libqrencode scores it: its lines'
  runs and patterns, its 2x2 blocks of one color, and how far from half its
  dark modules are."
  [{:keys [size dark]}]
  (let [at #(dark (+ %1 (* %2 size)))
        lines (concat (partition size dark)
                      (for [x (range size)] (map #(at x %) (range size))))
        blocks (count (for [y (range 1 size)
                            x (range 1 size)
                            :when (= (at x y) (at (dec x) y) (at x (dec y)) (at (dec x) (dec y)))]
                        1))
        cells (* size size)
        percent (quot (quot (+ (* 200 (count (filter true? dark))) cells) cells) 2)]
    (+ (* 10 (quot (abs (- percent 50)) 5))
       (* 3 blocks)
       (reduce + (map (comp line-penalty runs) lines)))))

(defn matrix
  "The modules of the QR code of `text` (as UTF-8), row by row, true for
  dark: with `mask` (0 to 7), or the one libqrencode would pick. Throws
  ex-info when it's longer than `max-bytes`."
  ([text]
   (matrix text nil))
  ([text mask]
   (let [data (map #(bit-and % 0xff) (.getBytes (str text) "UTF-8"))
         version (or (version-for (count data))
                     (throw (ex-info (format "too long for a QR code here: %d bytes, %d at most" (count data) max-bytes)
                                     {:type ::too-long})))
         base (place (function-patterns version) (interleaved (codewords data version) version))
         [_ best] (reduce (fn [[least _ :as best] m]
                            (let [p (penalty m)]
                              (if (or (nil? least) (< p least)) [p m] best)))
                          [nil nil]
                          (map #(masked base %) (if mask [mask] (range 8))))]
     (mapv vec (partition (:size best) (:dark best))))))

(defn ^:private dark-runs
  "The runs of dark modules of `row`: [[x length]...]."
  [row]
  (->> (map-indexed vector row)
       (partition-by second)
       (filter (comp second first))
       (map (fn [run] [(ffirst run) (count run)]))))

(defn svg
  "The QR code of `text` as an SVG image, dark on white, with a quiet zone
  of 2 modules around it. Throws ex-info when `text` is too long."
  [text]
  (let [rows (matrix text)
        margin 2
        width (+ (count rows) (* 2 margin))
        path (str/join (for [[y row] (map-indexed vector rows)
                             [x n] (dark-runs row)]
                         (format "M%d %dh%dv1h-%dz" (+ x margin) (+ y margin) n n)))]
    (format (str "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %d %d\" shape-rendering=\"crispEdges\">"
                 "<rect width=\"%d\" height=\"%d\" fill=\"#fff\"/><path d=\"%s\" fill=\"#000\"/></svg>")
            width width width width path)))
