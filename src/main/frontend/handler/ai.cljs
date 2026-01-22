(ns frontend.handler.ai
  "AI integration handler for Deep Dive feature"
  (:require [frontend.handler.editor :as editor-handler]
            [frontend.db :as db]
            [frontend.handler.notification :as notification]
            [promesa.core :as p]
            [clojure.string :as string]))

;; =============================================================================
;; Configuration
;; =============================================================================
;; To configure, set these in browser console or localStorage:
;;   localStorage.setItem("ai-api-key", "sk-your-key")
;;   localStorage.setItem("ai-api-endpoint", "https://api.openai.com/v1/chat/completions")
;;   localStorage.setItem("ai-model", "gpt-4")

(def ^:private default-endpoint "https://api.openai.com/v1/chat/completions")
(def ^:private default-model "gpt-4")

(defn- get-storage-item [key]
  (when (exists? js/localStorage)
    (.getItem js/localStorage key)))

(defn get-api-config []
  {:endpoint (or (get-storage-item "ai-api-endpoint") default-endpoint)
   :api-key  (get-storage-item "ai-api-key")
   :model    (or (get-storage-item "ai-model") default-model)})

;; =============================================================================
;; API
;; =============================================================================

(defn- build-prompt [content]
  (str "Explain this concept or translate this sentence in a deep, philosophical way: " content))

(defn- call-openai-api!
  "Calls an OpenAI-compatible API with the given prompt.
   Returns a promise that resolves to the response text."
  [prompt]
  (let [{:keys [endpoint api-key model]} (get-api-config)]
    (if (string/blank? api-key)
      (p/rejected (js/Error. "API key not set. Run in console: localStorage.setItem('ai-api-key', 'your-key')"))
      (-> (js/fetch endpoint
                    #js {:method "POST"
                         :headers #js {"Content-Type" "application/json"
                                       "Authorization" (str "Bearer " api-key)}
                         :body (js/JSON.stringify
                                #js {:model model
                                     :messages #js [#js {:role "user"
                                                         :content prompt}]
                                     :max_tokens 1000
                                     :temperature 0.7})})
          (p/then (fn [response]
                    (if (.-ok response)
                      (.json response)
                      (p/rejected (js/Error. (str "API request failed: " (.-status response)))))))
          (p/then (fn [data]
                    (let [content (-> data
                                      (aget "choices")
                                      (aget 0)
                                      (aget "message")
                                      (aget "content"))]
                      (string/trim (or content "")))))))))

;; =============================================================================
;; Main Entry Point
;; =============================================================================

(defn deep-dive!
  "Main entry point for Deep Dive feature.
   Fetches block content, calls AI API, and inserts response as child block."
  [block-id]
  (when-let [block (db/entity [:block/uuid block-id])]
    (let [content (:block/title block)]
      (if (string/blank? content)
        (notification/show! "Block is empty. Please add some content first." :warning)
        (do
          (notification/show! "🧠 Deep Dive: Thinking..." :info)
          (-> (call-openai-api! (build-prompt content))
              (p/then (fn [response]
                        (when-not (string/blank? response)
                          ;; Insert AI response as a child block
                          (editor-handler/api-insert-new-block!
                           (str "🧠 " response)
                           {:block-uuid block-id
                            :sibling? false  ; false = insert as child
                            :edit-block? false}))
                        (notification/show! "🧠 Deep Dive complete!" :success)))
              (p/catch (fn [error]
                         (js/console.error "Deep Dive error:" error)
                         (notification/show! (str "Deep Dive failed: " (.-message error)) :error)))))))))
