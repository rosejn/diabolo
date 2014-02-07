(ns diabolo.core
  (:use [nuroko.lab core charts]
        [nuroko.gui visual]
        [clojure.core.matrix])
  (:require [task.core :as task]
            [mikera.vectorz.core :as vectorz]
            [diabolo mnist]
            [overtone.at-at :as at])
  (:import [mikera.vectorz Op Ops])
  (:import [mikera.vectorz.ops ScaledLogistic Logistic Tanh])
  (:import [nuroko.coders CharCoder])
  (:import [mikera.vectorz AVector Vectorz]))


(def TIMERZ (at/mk-pool))

;; 60,000 training samples, 10,000 test
(def MNIST-CLASSES     (range 10))
(def MNIST-DATA        @diabolo.mnist/data-store)
(def MNIST-LABELS      @diabolo.mnist/label-store)
(def MNIST-TEST-DATA   @diabolo.mnist/test-data-store)
(def MNIST-TEST-LABELS @diabolo.mnist/test-label-store)

(defn denoising-task
  [data noise-ratio]
  (let [ins (vec data)]
    (proxy [nuroko.task.ExampleTask] [ins ins]
      (getInput [out-vec]
        (proxy-super getInput out-vec)
        (dotimes [i (ecount out-vec)]
          (when (> (rand) noise-ratio)
            (vectorz/set out-vec i 0.0)))))))


; Pure autoencoding task
;(def autoencode-task (identity-task data))

; Autoencode with corrupted input, forcing the network to filter out the noise
(defn autoencode-task
  [data noise]
  (denoising-task data noise))


(defn encoder
  [{:keys [input-size hidden-size encoder-activation sparsity-weight sparsity-target] :as config}]
  (stack
    ;;(offset :length input-size :delta -0.5)
    (neural-network :inputs input-size
                    :outputs hidden-size
                    :layers 1
                    ;; :max-weight-length 4.0
                    :output-op encoder-activation)
                    ;; :dropout 0.5)
    (sparsifier :length hidden-size :weight sparsity-weight)))


(defn decoder
  [{:keys [input-size hidden-size decoder-activation] :as config}]
  (stack
    ;(offset :length hidden-size :delta -0.5)
    (neural-network :inputs hidden-size
                    :outputs input-size
                    ;; :max-weight-length 4.0
                    :output-op decoder-activation
                    :layers 1)))


(defn autoencoder
  [{:keys [train-data noise-pct] :as config}]
  (let [enco    (encoder config)
        deco    (decoder config)
        model   (connect enco deco)
        task    (autoencode-task train-data noise-pct)
        trainer (supervised-trainer model task)]
    {:task task
     :encoder enco
     :decoder deco
     :model model
     :trainer trainer}))


(defn classifier
  [{:keys [classes train-labels train-data classifier-size]} ae]
  (let [num-coder (class-coder :values classes)
        task (mapping-task
               (apply hash-map
                      (interleave train-data train-labels))
               :output-coder num-coder)
        classifier (stack
                     ;(offset :length classifier-size :delta -0.5)
                     (neural-network :inputs classifier-size
                                     :output-op Ops/LOGISTIC
                                     :outputs (count classes)
                                     :layers 2))
        model   (connect (:encoder ae) classifier)
        trainer (supervised-trainer model task
                   ;;:loss-function nuroko.module.loss.CrossEntropyLoss/INSTANCE
                )]
    {:autoencoder ae
     :task task
     :classifier classifier
     :model model
     :trainer trainer
     :output-coder num-coder}))


(defn classify
  [classifier input-sample decoder]
  (->> input-sample
       (think classifier)
       (decode decoder)))


(defn evaluator-task
  [{:keys [test-data test-labels output-coder] :as config} classifier]
  (mapping-task (apply hash-map
                       (interleave test-data test-labels))
                :output-coder output-coder))


(defn feature-img
  [vector]
  ((image-generator :width 28
                    :height 28
                    :colour-function weight-colour-mono)
   vector))


(defn view-samples
  [data n]
  (show (map img (take n data))
        :title (format "First %d samples" n)))


(defn track-classification-error
  "Show chart of training error (blue) and test error (red)"
  [model classify-task test-task]
  (show (time-chart [#(evaluate-classifier classify-task model)
                     #(evaluate-classifier test-task model)]
                    :y-max 1.0)
        :title "Error rate"))


#_(defn show-results
  "Show classification results, errors are starred"
  []
  (show (map (fn [l r] (if (= l r) l (str r "*")))
             (take 100 labels)
             (map recognise (take 100 data)))
        :title "Classification results"))


;(let [rnet (.clone recognition-network)]
;  (reduce
;    (fn [acc i] (if (= (test-labels i) (->> (test-data i) (think rnet) (decode num-coder)))
;                  (inc acc) acc))
;    0 (range (count test-data))))

(defn show-class-separation
  [{:keys [test-data test-labels] :as config} classifier n]
  (show (class-separation-chart classifier
                                (take n test-data)
                                (take n test-labels))))


;(show (map feature-img (feature-maps recognition-network :scale 10)) :title "Recognition maps")
;(show (map feature-img (feature-maps reconstructor :scale 10)) :title "Round trip maps")

(defn show-reconstructions
  [model data n]
  (show
    (->> (take n data)
         (map (partial think model))
         (map img))
    :title (format "%d samples reconstructed" n)))


(defn show-features
  [layer scale]
  (show (map feature-img (feature-maps layer :scale scale))
        :title "Features"))


(defn run-experiment
  [{:keys [n-reconstructions train-data ae-train-secs mlp-train-secs learning-rate]
    :as config}]
  (let [ae     (autoencoder config)
        mlp    (classifier config ae)
        tester (evaluator-task config mlp)]

    (view-samples 25 train-data)

    (task/run {:repeat true}
      (do ((:trainer ae) (:model ae))
          (show-reconstructions (:model ae) train-data n-reconstructions)))

    (at/after (* 1000 ae-train-secs)
      (fn []
        (task/stop-all)
        (show-features (:encoder ae) 2)

        (track-classification-error (:task classifier) (:model classifier)
                                    tester)

        (task/run {:repeat true}
                  ((:trainer mlp) (:model mlp) :learn-rate learning-rate))))

    (at/after (+ (* 1000 ae-train-secs)
                 (* 1000 mlp-train-secs))
              (task/stop-all))

    {:autoencoder ae
     :classifier  mlp
     :evaluator   tester}))


(def config
  {:input-size      784
   :hidden-size     1200
   :classifier-size 400
   :noise-pct       0.3
   :sparsity-weight 0.2
   :sparsity-target 0.2
   :encoder-activation Ops/LOGISTIC
   :decoder-activation Ops/LOGISTIC
   :learning-rate 0.0001

   :classes       MNIST-CLASSES
   :train-data    MNIST-DATA
   :train-labels  MNIST-LABELS
   :test-data     MNIST-TEST-DATA
   :test-labels   MNIST-TEST-LABELS

   :ae-train-secs (* 5 60)
   :mlp-train-secs (* 5 60)
   :n-reconstructions 25
   })

(run-experiment config)
