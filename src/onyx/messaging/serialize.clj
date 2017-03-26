(ns onyx.messaging.serialize
  (:require [onyx.compression.nippy :refer [messaging-compress messaging-decompress]])
  (:import [org.agrona.concurrent UnsafeBuffer]
           [onyx.serialization MessageEncoder MessageDecoder MessageEncoder$SegmentsEncoder]))

(defprotocol PSegmentEncoder
  (add-message [this bs])
  (offset [this])
  (length [this])
  (wrap [this offset]))

(deftype SegmentsEncoder [^UnsafeBuffer buffer 
                          ^:unsynchronized-mutable start-offset
                          ^:unsynchronized-mutable offset]
  PSegmentEncoder
  (add-message [this bs]
    (let [len (int (alength ^bytes bs))
          _ (.putInt buffer offset len)
          new-offset (unchecked-add-int offset 4)
          new-count (short (unchecked-add-int (.getShort buffer start-offset) 1))]
      (.putBytes buffer new-offset bs)
      (.putShort buffer start-offset new-count)
      (set! offset (unchecked-add-int new-offset len))
      this))
  (offset [this] offset)
  (length [this]
    (.getShort buffer start-offset))
  (wrap [this new-offset] 
    (set! start-offset new-offset)
    (.putShort buffer new-offset (int 0))
    (set! offset (+ new-offset 2))
    this))


(def message-id ^:const (byte 0))
(def barrier-id ^:const (byte 1))
(def heartbeat-id ^:const (byte 2))
(def ready-id ^:const (byte 3))
(def ready-reply-id ^:const (byte 4))

; (defn message [replica-version short-id payload]
;   {:type message-id :replica-version replica-version :short-id short-id :payload payload})

(defn barrier [replica-version epoch short-id]
  {:type barrier-id :replica-version replica-version :epoch epoch :short-id short-id})

;; should be able to get rid of src-peer-id via short-id
(defn ready [replica-version src-peer-id short-id]
  {:type ready-id :replica-version replica-version :src-peer-id src-peer-id :short-id short-id})

(defn ready-reply [replica-version src-peer-id dst-peer-id session-id short-id]
  {:type ready-reply-id :replica-version replica-version :src-peer-id src-peer-id 
   :dst-peer-id dst-peer-id :session-id session-id :short-id short-id})

(defn heartbeat [replica-version epoch src-peer-id dst-peer-id session-id short-id]
  {:type heartbeat-id :replica-version replica-version :epoch epoch 
   :src-peer-id src-peer-id :dst-peer-id dst-peer-id :session-id session-id
   :short-id short-id})

(defn get-message-type [^UnsafeBuffer buf offset]
  (.getByte buf ^long offset))

(defn put-message-type [^UnsafeBuffer buf offset type-id] 
  (.putByte buf offset type-id))

(defn serialize ^UnsafeBuffer [msg]
  (let [bs ^bytes (messaging-compress msg)
        length (inc (alength bs))
        buf (UnsafeBuffer. (byte-array length))]
    (put-message-type buf 0 (:type msg))
    (.putBytes buf 1 bs)
    buf))  

(defn deserialize [^UnsafeBuffer buf offset length]
  (let [bs (byte-array length)] 
    (.getBytes buf offset bs)
    (messaging-decompress bs)))

(defn add-segment-payload! [^MessageEncoder encoder segments]
  (let [seg-encoder (loop [^MessageEncoder$SegmentsEncoder enc (.segmentsCount encoder (count segments))
                           v (first segments) 
                           vs (rest segments)]
                      (let [bs ^bytes (messaging-compress v) 
                            cnt ^int (alength bs)]
                        (when v 
                          (recur (.putSegmentBytes (.next enc) bs 0 cnt)
                                 (first vs) 
                                 (rest vs)))))]
    (.encodedLength encoder)))

(defn wrap-message-encoder ^MessageEncoder [^UnsafeBuffer buf offset]
  (-> (MessageEncoder.)
      (.wrap buf offset)))

(defn wrap-message-decoder ^MessageDecoder [^UnsafeBuffer buf offset]
  (-> (MessageDecoder.)
      (.wrap buf offset MessageDecoder/BLOCK_LENGTH 0)))

(defn into-segments! [^MessageDecoder decoder ^bytes bs segments]
  (loop [dc (.segments decoder) cnt 0]
    (when (.hasNext dc)
      (.getSegmentBytes dc bs 0 (.segmentBytesLength dc))
      (conj! segments (messaging-decompress bs))
      (recur (.next dc) (inc cnt))))
  segments)
