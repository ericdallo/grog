(ns grog.oauth-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [grog.oauth :as oauth]
            [grog.test-helpers :as h]))

(deftest pkce
  ;; RFC 7636, appendix B.
  (is (= "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
         (oauth/challenge "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")))
  (let [{:keys [verifier challenge]} (oauth/pkce)]
    (is (re-matches #"[A-Za-z0-9_-]{43}" verifier))
    (is (= challenge (oauth/challenge verifier))))
  (is (not= (oauth/random-token) (oauth/random-token))))

(deftest form-encoding
  (is (= "a=1&redirect_uri=http%3A%2F%2Flocalhost%3A1455%2Fauth%2Fcallback&scope=openid+email"
         (oauth/form-encode [[:a 1]
                             [:redirect_uri "http://localhost:1455/auth/callback"]
                             [:scope "openid email"]])))
  (is (= {:code "abc" :state "x y" :empty ""}
         (oauth/form-decode "code=abc&state=x+y&empty=")))
  (is (= {} (oauth/form-decode nil))))

(deftest page-escapes-text
  (let [html (oauth/page "Sign-in failed" "<script>alert('x')</script> & more")]
    (is (str/includes? html "<p>&lt;script&gt;alert('x')&lt;/script&gt; &amp; more</p>"))
    (is (not (str/includes? html "<script>")))))

(deftest jwt-claims
  (is (= {"email" "a@b.c"} (oauth/jwt-claims (h/jwt {"email" "a@b.c"}))))
  (is (nil? (oauth/jwt-claims "not-a-jwt")))
  (is (nil? (oauth/jwt-claims nil))))
