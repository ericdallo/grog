(ns grog.skin
  "grog's skin (skin/ in the repo): a page showing what grog is up to, served
  at / with the files next to it, from the `skin.dir` setting. It watches
  `GET /v1/events` (grog.events), from the same origin."
  (:require [babashka.fs :as fs]))

;; What the skin is made of: the page, and its fonts
;; (body/packages/grog-skin.nix). Its QR codes are grog's (`GET /v1/qr`).
(def ^:private content-types
  {"html" "text/html; charset=utf-8"
   "css" "text/css; charset=utf-8"
   "js" "text/javascript; charset=utf-8"
   "ttf" "font/ttf"})

;; The page only talks to grog, and shows nothing from elsewhere but the
;; pictures of what plays (grog-music's artwork, https).
(def ^:private policy
  "default-src 'self'; img-src 'self' https:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'")

(defn file
  "The file of `dir` at `uri` (/ being index.html), or nil: only plain names
  (no .., no hidden files, nothing to decode), of the types above."
  [dir uri]
  (when (and dir (re-matches #"/|(/[A-Za-z0-9_-][A-Za-z0-9._-]*)+" uri))
    (let [f (fs/path dir (if (= "/" uri) "index.html" (subs uri 1)))]
      (when (and (fs/regular-file? f) (contains? content-types (fs/extension f)))
        f))))

(defn response
  "The response serving `uri` from the skin in `dir`, or nil."
  [dir uri]
  (when-let [f (file dir uri)]
    (let [type (content-types (fs/extension f))]
      {:status 200
       :headers (cond-> {"Content-Type" type
                         "Cache-Control" "no-cache"
                         "X-Content-Type-Options" "nosniff"}
                  (= "html" (fs/extension f)) (assoc "Content-Security-Policy" policy))
       :body (fs/file f)})))
