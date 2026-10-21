(ns grog.providers.openai.citations-test
  (:require [clojure.test :refer [deftest is testing]]
            [grog.providers.openai.citations :as citations]))

(defn ^:private stream
  "The text emitted for `chunks`, streamed in that order."
  [chunks]
  (let [[out pending] (reduce (fn [[out pending] chunk]
                                (let [[emitted held] (citations/strip pending chunk)]
                                  [(str out emitted) held]))
                              ["" ""]
                              chunks)]
    (str out (citations/finish pending))))

(def ^:private cited
  (str "O dólar está em cinco reais. ([cotacaododia.com](https://cotacaododia.com/dolar/?utm_source=openai))"
       "\n\nNo exterior (veja só), [nada] muda. ([apnews.com](https://apnews.com/article/0251?utm_source=openai))"))

(def ^:private clean
  "O dólar está em cinco reais.\n\nNo exterior (veja só), [nada] muda.")

(deftest drops-citations
  (testing "in one go"
    (is (= clean (stream [cited]))))
  (testing "however the text is split"
    (doseq [i (range (inc (count cited)))]
      (is (= clean (stream [(subs cited 0 i) (subs cited i)])) (str "split at " i))))
  (testing "a character at a time"
    (is (= clean (stream (map str cited))))))

(deftest leaves-the-rest-alone
  (testing "parentheses and brackets that aren't citations"
    (doseq [text ["Quatro a um (Palmeiras)." "Veja [isto] (ou aquilo)." "Um ( solto e um [ solto."
                  "Um link [x](y) qualquer." "Fim (de [tudo](aqui) mesmo)."]]
      (is (= text (stream (map str text))) text)))
  (testing "an unfinished citation, at the end"
    (is (= "Fim. ([site.com](https://site" (stream ["Fim. ([site.com](https://site"])))))

(deftest holds-back-only-what-it-must
  (is (= ["Vou ver o tempo." ""] (citations/strip "" "Vou ver o tempo.")))
  (testing "whitespace, until what follows shows it's not before a citation"
    (is (= ["Faz sol." " "] (citations/strip "" "Faz sol. ")))
    (is (= [" Amanhã também." ""] (citations/strip " " "Amanhã também."))))
  (testing "what could still be a citation, until it isn't"
    (is (= ["Faz sol." " ([tempo.com](https://tem"] (citations/strip "" "Faz sol. ([tempo.com](https://tem")))
    (is (= ["" " ("] (citations/strip "" " (")))
    (is (= [" (Palmeiras" ""] (citations/strip " (" "Palmeiras")))))
