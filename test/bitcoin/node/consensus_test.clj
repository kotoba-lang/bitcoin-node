(ns bitcoin.node.consensus-test
  (:require [bitcoin.node.consensus :as consensus]
            [clojure.test :refer [deftest is]]))

(def genesis
  "0100000000000000000000000000000000000000000000000000000000000000000000003ba3edfd7a7b12b27ac72c3e67768f617fc81bc3888a51323a9fb8aa4b1e5e4a29ab5f49ffff001d1dac2b7c0101000000010000000000000000000000000000000000000000000000000000000000000000ffffffff4d04ffff001d0104455468652054696d65732030332f4a616e2f32303039204368616e63656c6c6f72206f6e206272696e6b206f66207365636f6e64206261696c6f757420666f722062616e6b73ffffffff0100f2052a01000000434104678afdb0fe5548271967f1a67130b7105cd6a828e03909a67962e0ea1f61deb649f6bc3f4cef38c4f35504e51ec112de5c384df7ba0b8d578a4c702b6bf11d5fac00000000")

(def block-one
  "010000006fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000982051fd1e4ba744bbbe680e1fee14677ba1a3c3540bf7b1cdb606e857233e0e61bc6649ffff001d01e362990101000000010000000000000000000000000000000000000000000000000000000000000000ffffffff0704ffff001d0104ffffffff0100f2052a0100000043410496b538e853519c726a2c91e61ec11600ae1390813a627c66fb8be7947be63c52da7589379515d4e0a604f8141781e62294721166bf621e73a82cbf2342c858eeac00000000")

(defn hex->bytes [hex]
  (mapv #(Integer/parseInt (apply str %) 16) (partition 2 hex)))

(deftest embedded-consensus-validates-before-publishing-state
  (let [node (consensus/open {:network :mainnet
                              :genesis-bytes (hex->bytes genesis)})
        before (consensus/consensus-status node)
        after (consensus/accept-block! node (hex->bytes block-one)
                                       2000000000)]
    (is (consensus/ready? node))
    (is (= 0 (:height before)))
    (is (= 1 (:height after)))
    (is (= 2 (:utxo-count after)))
    (is (:fully-validated? after))
    (is (false? (:persistent? after)))))

(deftest embedded-consensus-allows-an-explicit-differential-verifier
  (let [calls (atom 0)
        node (consensus/open
              {:network :mainnet :genesis-bytes (hex->bytes genesis)
               :verify-script
               (fn [& _] (swap! calls inc) true)})]
    (is (consensus/ready? node))
    ;; Genesis and block one contain coinbase transactions only.
    (consensus/accept-block! node (hex->bytes block-one) 2000000000)
    (is (zero? @calls))))
