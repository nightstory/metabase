(ns metabase.integrations.ds_webhook
  (:require [clojure.tools.logging :as log]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [metabase.models.setting
             :as    setting
             :refer [defsetting]]
            [metabase.util.i18n :refer [deferred-tru trs tru]]
            [metabase.util.schema :as su]
            [metabase.util :as u]
            [schema.core :as s]
            [metabase.public-settings :as public-settings]
            [metabase.util.urls :as urls]
            [metabase.pulse.parameters :as params]))

;; Define a setting which captures our webhook url
(defsetting ds-webhook-url (deferred-tru "Your dashboard subscription webhook URL"))

(defn webhook-configured?
  "Is Webhook integration configured?"
  []
  (boolean (seq (ds-webhook-url))))

(def ^:private NonEmptyByteArray
  (s/constrained
   (Class/forName "[B")
   not-empty
   "Non-empty byte array"))

(s/defn post-event!
  "Calls your webhook"
  [file :- NonEmptyByteArray,
   {dasbboard-description :description, dashboard-name :name, :as dashboard },
   dashcard,
   {pulse-id :id, pulse-dashboard-id :dashboard_id, pulse-name :name, :as pulse},
   {card-id :id, card-name :name, :as card}]
  {:pre [(seq (ds-webhook-url))]}
  (let [response (http/post (ds-webhook-url)
                            {:multipart [
                                         {:name "image",
                                          :content file}
                                         {:name "site_url",
                                          :content (str (public-settings/site-url))}
                                         {:name "dashboard_id",
                                          :content (str pulse-dashboard-id)}
                                         {:name "dashboard_name",
                                          :content (str dashboard-name)}
                                         {:name    "dashboard_description",
                                          :content (str dasbboard-description)}
                                         {:name    "dashboard_url",
                                          :content (str (if dashboard
                                                          (params/dashboard-url (u/the-id dashboard) (params/parameters pulse dashboard))
                                                          (if pulse-dashboard-id
                                                            (params/dashboard-url pulse-dashboard-id (params/parameters pulse dashboard))
                                                            ;; For some reason, the pulse model does not contain the id
                                                            (urls/pulse-url 0))))}
                                         {:name "pulse_id",
                                          :content (str pulse-id)}
                                         {:name "pulse_name",
                                          :content (str pulse-name)}
                                         {:name "card_id",
                                          :content (str card-id)}
                                         {:name "card_name",
                                          :content (str card-name)}
                                         {:name "card_description",
                                          :content (str (-> dashcard :visualization_settings :card.description))}
                                         {:name "card_url",
                                          :content (str (urls/card-url card-id))}]})]
    (if (= 200 (:status response))
      (u/prog1 (get-in (:body response) [:file :url_private])
               (log/debug "Uploaded image" <>))
      (log/warn "Error uploading file to Webhook:" (u/pprint-to-str response)))))
