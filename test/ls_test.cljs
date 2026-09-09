;; test/ls_test.cljs — build the command and compare it with the system
;; cat, byte for byte.
;;
;; This does not assert that the guest COMPILES. It compiles it, packages it
;; into a standalone binary, runs that binary, and compares its bytes and its
;; exit status against /bin/cat -- because `:ok true` from a compiler means
;; the artifact was built, not that it is right, and this repository's whole
;; claim is about what the artifact does.
;;
;; Every case is passed as an ARGUMENT VECTOR, never through a shell. zsh does
;; not word-split an unquoted variable, so `cmd $args` hands the whole string
;; over as ONE argument -- which silently turns a multi-argument test into a
;; single-argument one and makes the join logic and `-n` look tested when they
;; are not. Measured 2026-09-09: exactly that mistake made `-n hi` and
;; `a b c d e` pass before either was implemented or exercised.
;;
;;   AMU_HOME=<amu checkout> nbb test/echo_test.cljs
;;
;; Exits 0 when every case matches, 1 on any difference, and 2 when it could
;; not run at all -- a distinct code, so "did not run" is never read as "ran
;; and found nothing".
(ns ls-test
  (:require [clojure.string :as str] ["fs" :as fs] ["path" :as path] ["os" :as os]))

(def cp (js/require "node:child_process"))

(defn- run [cmd args opts]
  (let [r (.spawnSync cp cmd (clj->js args)
                      (clj->js (merge {:encoding "buffer"} opts)))]
    {:status (.-status r) :out (.-stdout r) :err (.-stderr r)}))

(defn- refuse [message]
  (println (pr-str {:ok false :phase :setup :message message}))
  (.exit js/process 2))

(def amu-home
  (or (.-AMU_HOME js/process.env)
      (let [guess (.resolve path (.cwd js/process) ".." ".." "kotoba-lang" "amu")]
        (when (.existsSync fs (.join path guess "bin" "amu")) guess))))

(def system-ls "/bin/ls")

;; A directory of fixtures, and the cases over them. Each case is an argv,
;; and each is here because it separates a right implementation from a wrong
;; one that passes the others:
;;
;;   one file           -- the basic contract
;;   two files          -- concatenated in ORDER, with nothing added between
;;   the same file twice-- an operand is not deduplicated
;;   an EMPTY file      -- reads as the empty string, which must not end the
;;                         loop the way "past the last operand" does
;;   empty then content -- the same trap from the other side
;;   no trailing newline-- cat adds nothing of its own
;;   binary-ish bytes   -- high bytes survive the round trip
;;   no operands        -- POSIX reads stdin; there is no stdin capability,
;;                         so this asserts what it ACTUALLY does (nothing),
;;                         not what POSIX says
(def fixtures
  {"plain"   ["a.txt" "b.txt"]
   "hidden"  [".dot" "a.txt"]
   "onlydot" [".only"]
   "empty"   []
   "mixed"   ["-dash" "1one" "Zebra" "_under" "a b" "apple" "z-last" "\u00e9" "\u65e5\u672c" ".hidden"]
   "sub"     ["f.txt"]})

;; Each case is a directory, and each is here because it separates a right
;; implementation from a wrong one that passes the others:
;;
;;   plain    -- one entry per line, in the host's order
;;   hidden   -- entries beginning with a period are omitted
;;   onlydot  -- a directory whose ONLY entry is hidden prints nothing, which
;;               is different from printing an empty line
;;   empty    -- and so does an empty directory
;;   mixed    -- punctuation, digits, case, a space, and MULTI-BYTE names.
;;               This is the case that caught the real bug: testing "begins
;;               with a period" as (string-substring name 0 1) cuts a
;;               code point in half when the first character is multi-byte,
;;               and the guest listed the ASCII entries and then trapped
;;               SIGILL on `é`.
(def cases [["plain"] ["hidden"] ["onlydot"] ["empty"] ["mixed"] ["sub"]])

(when-not amu-home (refuse "set AMU_HOME to an amu checkout"))
(let [amu (.join path amu-home "bin" "amu")
      packager (.join path amu-home "scripts" "package-command.cljs")]
  (when-not (.existsSync fs amu) (refuse (str "no amu at " amu)))
  (when-not (.existsSync fs packager) (refuse (str "no packager at " packager)))
  (when-not (.existsSync fs system-ls) (refuse (str "no " system-ls " to compare against")))
  (let [tmp (.mkdtempSync fs (.join path (.tmpdir os) "org-ieee-cat-"))
        src (.resolve path (.cwd js/process) "ls" "core.kotoba")
        policy (.join path tmp "policy.edn")
        kexe (.join path tmp "ls.kexe")
        blob (.join path tmp "ls.bin")
        exe (.join path tmp "ls")
        exe-big (.join path tmp "ls-big")]
    (.writeFileSync fs policy "{:allow #{[:cap/call 34] [:cap/call 37] [:cap/call 38]}}" "utf8")
    ;; The fixtures live in the tree the binary is packaged for. The native
    ;; loader refuses a relative request outright, so operands are absolute.
    (let [data (.join path tmp "data")]
      (.mkdirSync fs data)
      (doseq [[dir entries] fixtures]
        (.mkdirSync fs (.join path data dir))
        (doseq [e entries]
          (.writeFileSync fs (.join path data dir e) "" "utf8"))))
    (let [c (run "node" [amu "compile" src "--target" "aarch64-macos" "--jvm-free"
                         "--policy" policy "--output" kexe] {})]
      (when (not= 0 (:status c))
        (refuse (str "compile failed: " (str (:err c)) (str (:out c))))))
    (let [e (run "node" [amu "extract-native" kexe "--symbol" "main" "--output" blob] {})
          _ (when (not= 0 (:status e)) (refuse (str "extract failed: " (str (:err e)))))
          report (str (:out e))
          offset (second (re-find #":offset (\d+)" report))]
      (when-not offset (refuse (str "no :offset in the extract report: " report)))
      ;; TWO binaries from the same code: one with the loader's default
      ;; string-arena budget and one with a raised budget. The pair is what
      ;; makes the ceiling below a measurement instead of a claim -- a single
      ;; binary could only show that some size works and some does not, not
      ;; that the bound is the arena and that it moves.
      (doseq [[out extra] [[exe []]]]
        (let [p (run "nbb" (into [packager "--code" blob "--offset" offset "--isa" "aarch64"
                                  "--allow" "34,37,38"
                                  "--browse-scope" (.realpathSync fs (.join path tmp "data"))
                                  "--output" out]
                                 extra) {})]
          (when (not= 0 (:status p)) (refuse (str "package failed: " (str (:err p))))))))
    ;; Now the only thing that matters: run it.
    (let [results
          (for [names cases]
            (let [argv (mapv #(.join path (.realpathSync fs (.join path tmp "data")) %) names)
                  k (run exe argv {})
                  ;; LC_ALL=C: the host sorts by bytes, and that is the
                  ;; POSIX locale's order. See the header.
                  s (run system-ls argv
                         {:env (let [e (js/Object.assign #js {} (.-env js/process))]
                                 (aset e "LC_ALL" "C")
                                 e)})
                  same? (and (= (.toString (:out k) "base64") (.toString (:out s) "base64"))
                             (= (:status k) (:status s)))]
              {:argv names :ok same? :kotoba (.toString (:out k) "utf8")
               :system (.toString (:out s) "utf8")
               :exit [(:status k) (:status s)]}))
          bad (remove :ok results)]
      (doseq [r results]
        (println (str (if (:ok r) "  ok   " "  FAIL ")
                      (pr-str (:argv r))
                      " -> " (pr-str (:kotoba r))
                      (when-not (:ok r) (str " but " system-ls " says " (pr-str (:system r))
                                             " exits " (pr-str (:exit r))))))) 
      (println (pr-str {:ok (empty? bad) :cases (count results) :failed (count bad)}))
      (.exit js/process (if (seq bad) 1 0)))))