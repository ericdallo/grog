(ns grog.qr-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [grog.qr :as qr]))

(def ^:private texts
  "Texts filling versions 1 to 10 at level M."
  ["a"
   "http://grognix.local"
   "http://192.168.15.10:8485/c/K7Q2M9AB4X"
   "http://[fe80::1234:5678:9abc:def0]:8485/c/K7Q2M9AB4XYZ"
   (str "https://auth.openai.com/codex/device?" (apply str (repeat 40 "x")))
   (apply str (repeat 100 "y"))
   (apply str (repeat 120 "z"))
   (apply str (repeat 150 "0"))
   (apply str (repeat 175 "/"))
   (apply str (repeat 213 "A"))
   "Olá, café com pão ☕"])

(defn ^:private qrencode
  "What qrencode draws for `text`, in byte mode at level M, with `mask`:
  its modules, row by row, true for dark."
  [text]
  (let [{:keys [out]} (p/shell {:out :string} "qrencode" "-8" "-l" "M" "-m" "0" "-t" "ASCII" "-o" "-" text)]
    (mapv (fn [line] (mapv #(= \# (first %)) (partition 2 line))) (str/split-lines out))))

(deftest the-same-code-as-qrencode
  (if-not (fs/which "qrencode")
    (println "qr-test: no qrencode here, so its codes aren't compared (the package's tests compare them)")
    (doseq [text texts]
      (is (= (qrencode text) (qr/matrix text)) text))))

(defn ^:private format-bits
  "The format information read off the top left of `rows`, unmasked: [level
  mask]."
  [rows]
  (let [at (fn [x y] (if (get-in rows [y x]) 1 0))
        places (concat (for [i (range 6)] [8 i]) [[8 7] [8 8] [7 8]] (for [i (range 9 15)] [(- 14 i) 8]))
        b (bit-xor 0x5412 (reduce + (map-indexed (fn [i [x y]] (bit-shift-left (at x y) i)) places)))]
    [(bit-shift-right b 13) (bit-and 7 (bit-shift-right b 10))]))

(deftest what-a-code-is-made-of
  (testing "the smallest version holding the text"
    (is (= [1 1 2 3 4 10] (map qr/version-for [0 14 15 42 43 213])))
    (is (nil? (qr/version-for 214)))
    (doseq [[text version] (map vector texts (range 1 11))]
      (is (= (+ 17 (* 4 version)) (count (qr/matrix text))) text)))
  (testing "a finder pattern in three corners"
    (let [rows (qr/matrix "http://192.168.15.10:8485/c/K7Q2M9AB4X")
          n (count rows)
          finder? (fn [x0 y0]
                    (every? (fn [[dx dy]] (= (not= 2 (max (abs (- dx 3)) (abs (- dy 3))))
                                             (get-in rows [(+ y0 dy) (+ x0 dx)])))
                            (for [dy (range 7) dx (range 7)] [dx dy])))]
      (is (finder? 0 0))
      (is (finder? (- n 7) 0))
      (is (finder? 0 (- n 7)))))
  (testing "level M, and each mask when asked for one"
    (doseq [mask (range 8)]
      (is (= [0 mask] (format-bits (qr/matrix "grog" mask))))))
  (testing "too long"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"too long for a QR code here: 214 bytes, 213 at most"
                          (qr/matrix (apply str (repeat 214 "a")))))))

(deftest as-an-image
  (let [svg (qr/svg "http://192.168.15.10:8485/c/K7Q2M9AB4X")]
    (is (str/starts-with? svg "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 33 33\""))
    (is (str/includes? svg "<rect width=\"33\" height=\"33\" fill=\"#fff\"/>"))
    (testing "the top left finder pattern's first row, past the quiet zone"
      (is (str/includes? svg "<path d=\"M2 2h7v1h-7z")))))
