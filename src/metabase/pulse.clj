(ns metabase.pulse
  "Public API for sending Pulses."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [metabase.email :as email]
            [metabase.email.messages :as messages]
            [metabase.integrations.slack :as slack]
            [metabase.integrations.telegram :as telegram]
            [metabase.integrations.ds_webhook :as ds_webhook]
            [metabase.models.card :refer [Card]]
            [metabase.models.dashboard :refer [Dashboard]]
            [metabase.models.dashboard-card :refer [DashboardCard]]
            [metabase.models.database :refer [Database]]
            [metabase.models.pulse :as pulse :refer [Pulse]]
            [metabase.public-settings :as public-settings]
            [metabase.pulse.markdown :as markdown]
            [metabase.pulse.parameters :as params]
            [metabase.pulse.render :as render]
            [metabase.pulse.util :as pu]
            [metabase.query-processor :as qp]
            [metabase.query-processor.dashboard :as qp.dashboard]
            [metabase.query-processor.timezone :as qp.timezone]
            [metabase.server.middleware.session :as session]
            [metabase.util :as u]
            [metabase.util.i18n :refer [trs tru]]
            [metabase.util.ui-logic :as ui]
            [metabase.util.urls :as urls]
            [schema.core :as s]
            [toucan.db :as db])
  (:import metabase.models.card.CardInstance))

;;; ------------------------------------------------- PULSE SENDING --------------------------------------------------

(defn- execute-dashboard-subscription-card
  [owner-id dashboard dashcard card-or-id parameters]
  (try
    (let [card-id (u/the-id card-or-id)
          card    (Card :id card-id)
          result  (session/with-current-user owner-id
                    (qp.dashboard/run-query-for-dashcard-async
                     :dashboard-id  (u/the-id dashboard)
                     :card-id       card-id
                     :context       :pulse ; TODO - we should support for `:dashboard-subscription` and use that to differentiate the two
                     :export-format :api
                     :parameters    parameters
                     :middleware    {:process-viz-settings? true
                                     :js-int-to-string?     false}
                     :run           (fn [query info]
                                      (qp/process-query-and-save-with-max-results-constraints!
                                       (assoc query :async? false)
                                       info))))]
      {:card     card
       :dashcard dashcard
       :result   result})
    (catch Throwable e
      (log/warn e (trs "Error running query for Card {0}" card-or-id)))))

(defn- dashcard-comparator
  "Comparator that determines which of two dashcards comes first in the layout order used for pulses.
  This is the same order used on the frontend for the mobile layout. Orders cards left-to-right, then top-to-bottom"
  [dashcard-1 dashcard-2]
  (if-not (= (:row dashcard-1) (:row dashcard-2))
    (compare (:row dashcard-1) (:row dashcard-2))
    (compare (:col dashcard-1) (:col dashcard-2))))

(defn- execute-dashboard
  "Fetch all the dashcards in a dashboard for a Pulse, and execute non-text cards"
  [{pulse-creator-id :creator_id, :as pulse} dashboard & {:as options}]
  (let [dashboard-id      (u/the-id dashboard)
        dashcards         (db/select DashboardCard :dashboard_id dashboard-id)
        ordered-dashcards (sort dashcard-comparator dashcards)]
    (for [dashcard ordered-dashcards]
      (if-let [card-id (:card_id dashcard)]
        (execute-dashboard-subscription-card pulse-creator-id dashboard dashcard card-id (params/parameters pulse dashboard))
        ;; For virtual cards, return the viz settings map directly
        (-> dashcard :visualization_settings)))))

(defn- database-id [card]
  (or (:database_id card)
      (get-in card [:dataset_query :database])))

(s/defn defaulted-timezone :- s/Str
  "Returns the timezone ID for the given `card`. Either the report timezone (if applicable) or the JVM timezone."
  [card :- CardInstance]
  (or (some-> card database-id Database qp.timezone/results-timezone-id)
      (qp.timezone/system-timezone-id)))

(defn- first-question-name [pulse]
  (-> pulse :cards first :name))

(defn- alert-condition-type->description [condition-type]
  (case (keyword condition-type)
    :meets (trs "reached its goal")
    :below (trs "gone below its goal")
    :rows  (trs "results")))

(def ^:private ^:dynamic *slack-mrkdwn-length-limit*
  3000)

(defn- truncate-mrkdwn
  "If a mrkdwn string is greater than Slack's length limit, truncates it to fit the limit and
  adds an ellipsis character to the end."
  [mrkdwn]
  (if (> (count mrkdwn) *slack-mrkdwn-length-limit*)
    (-> mrkdwn
        (subs 0 (dec *slack-mrkdwn-length-limit*))
        (str "…"))
    mrkdwn))

(defn create-slack-attachment-data
  "Returns a seq of slack attachment data structures, used in `create-and-upload-slack-attachments!`"
  [card-results]
  (let [{channel-id :id} (slack/files-channel)]
    (->> (for [card-result card-results]
           (let [{{card-id :id, card-name :name, :as card} :card, dashcard :dashcard, result :result} card-result]
             (if (and card result)
               {:title           (or (-> dashcard :visualization_settings :card.title)
                                     card-name)
                :rendered-info   (render/render-pulse-card :inline (defaulted-timezone card) card dashcard result)
                :title_link      (urls/card-url card-id)
                :attachment-name "image.png"
                :channel-id      channel-id
                :fallback        card-name}
               (let [mrkdwn (markdown/process-markdown (:text card-result) :mrkdwn)]
                 (when (not (str/blank? mrkdwn))
                   {:blocks [{:type "section"
                              :text {:type "mrkdwn"
                                     :text (truncate-mrkdwn mrkdwn)}}]})))))
         (remove nil?))))

(defn- subject
  [{:keys [name cards dashboard_id]}]
  (if (or dashboard_id
          (some :dashboard_id cards))
    name
    (trs "Pulse: {0}" name)))

(defn- slack-dashboard-header
  "Returns a block element that includes a dashboard's name, creator, and filters, for inclusion in a
  Slack dashboard subscription"
  [pulse dashboard]
  (let [header-section  {:type "header"
                         :text {:type "plain_text"
                                :text (subject pulse)
                                :emoji true}}
        creator-section {:type   "section"
                         :fields [{:type "mrkdwn"
                                   :text (str "Sent by " (-> pulse :creator :common_name))}]}
        filters         (params/parameters pulse dashboard)
        filter-fields   (for [filter filters]
                          {:type "mrkdwn"
                           :text (str "*" (:name filter) "*\n" (params/value-string filter))})
        filter-section  (when (seq filter-fields)
                          {:type   "section"
                           :fields filter-fields})]
    (if filter-section
      {:blocks [header-section filter-section creator-section]}
      {:blocks [header-section creator-section]})))

(defn- slack-dashboard-footer
  "Returns a block element with the footer text and link which should be at the end of a Slack dashboard subscription."
  [pulse dashboard]
  {:blocks
   [{:type "divider"}
    {:type "context"
     :elements [{:type "mrkdwn"
                 :text (str "<" (params/dashboard-url (u/the-id dashboard) (params/parameters pulse dashboard)) "|"
                            "*Sent from " (public-settings/site-name) "*>")}]}]})

(def slack-width
  "Width of the rendered png of html to be sent to slack."
  1200)

(defn create-and-upload-slack-attachments!
  "Create an attachment in Slack for a given Card by rendering its result into an image and uploading
  it. Slack-attachment-uploader is a function which takes image-bytes and an attachment name, uploads the file, and
  returns an image url, defaulting to slack/upload-file!.

  Nested `blocks` lists containing text cards are passed through unmodified."
  ([attachments] (create-and-upload-slack-attachments! attachments slack/upload-file!))
  ([attachments slack-attachment-uploader]
   (letfn [(f [a] (select-keys a [:title :title_link :fallback]))]
     (reduce (fn [processed {:keys [rendered-info attachment-name channel-id] :as attachment-data}]
               (conj processed (if (:blocks attachment-data)
                                 attachment-data
                                 (if (:render/text rendered-info)
                                   (-> (f attachment-data)
                                       (assoc :text (:render/text rendered-info)))
                                   (let [image-bytes (render/png-from-render-info rendered-info slack-width)
                                         image-url   (slack-attachment-uploader image-bytes attachment-name channel-id)]
                                     (-> (f attachment-data)
                                         (assoc :image_url image-url)))))))
             []
             attachments))))

(defn- is-card-empty?
  "Check if the card is empty"
  [card]
  (if-let [result (:result card)]
    (or (zero? (-> result :row_count))
        ;; Many aggregations result in [[nil]] if there are no rows to aggregate after filters
        (= [[nil]]
           (-> result :data :rows)))
    ;; Text cards have no result; treat as empty
    true))

(defn- are-all-cards-empty?
  "Do none of the cards have any results?"
  [results]
  (every? is-card-empty? results))

(defn- goal-met? [{:keys [alert_above_goal], :as pulse} [first-result]]
  (let [goal-comparison      (if alert_above_goal <= >=)
        goal-val             (ui/find-goal-value first-result)
        comparison-col-rowfn (ui/make-goal-comparison-rowfn (:card first-result)
                                                            (get-in first-result [:result :data]))]

    (when-not (and goal-val comparison-col-rowfn)
      (throw (ex-info (tru "Unable to compare results to goal for alert.")
                      {:pulse  pulse
                       :result first-result})))
    (some (fn [row]
            (goal-comparison goal-val (comparison-col-rowfn row)))
          (get-in first-result [:result :data :rows]))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                         Telegram                                                               |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn- telegram-title-url
  "Returns dashboard or pulse url"
  [{pulse-dashboard-id :dashboard_id, :as pulse}
   dashboard]
  (if dashboard
    (params/dashboard-url (u/the-id dashboard) (params/parameters pulse dashboard))
    (if pulse-dashboard-id
      (params/dashboard-url pulse-dashboard-id (params/parameters pulse dashboard))
      ;; For some reason, the pulse model does not contain the id
      (urls/pulse-url 0))))

(defn- telegram-pulse-alert-message
  "Sends a message via Telegram, using the image and the pulse/dashboard name and url, and the card name and url as a caption"
  [{card-name :name, :as card}
   {pulse-name :name, pulse-dashboard-id :dashboard_id, :as pulse}
   dashboard]
  (let [title-url (telegram-title-url pulse dashboard)
        title-text (if dashboard (:name dashboard) pulse-name)
        subtitle-url (urls/card-url (u/the-id card))
        subtitle-text card-name]
    (format "[*%s*](%s) → [%s](%s)" title-text title-url subtitle-text subtitle-url)))

(def telegram-width
  "Width of the rendered png of html to be sent to Telegram."
  510)

(defn post-telegram-message!
  "Post a photo for a given Card by rendering its result into an image and sending it."
  [card-results chat-id pulse dashboard]
  (doall (for [{{card-id :id, card-name :name, :as card} :card, result :result, dashcard :dashcard} card-results]
           (let [image-byte-array (render/render-pulse-card-to-png (defaulted-timezone card) card result telegram-width dashcard)]
             (telegram/post-photo! image-byte-array
                                   (telegram-pulse-alert-message card pulse dashboard)
                                   chat-id)))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                         Webhook                                                                |
;;; +----------------------------------------------------------------------------------------------------------------+

(def webhook-width
  "Width of the rendered png of html to be sent to Webhook."
  512)

(defn send-webhook-event!
  "Post a photo for a given Card by rendering its result into an image and sending it, with other info attached"
  [card-results pulse dashboard]
  (doall (for [{{card-id :id, card-name :name, :as card} :card, result :result, dashcard :dashcard} card-results]
           (let [image-byte-array (render/render-pulse-card-to-png (defaulted-timezone card) card result webhook-width dashcard)]
             (ds_webhook/post-event! image-byte-array dashboard dashcard pulse card)))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                         Creating Notifications To Send                                         |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn- alert-or-pulse [pulse]
  (if (:alert_condition pulse)
    :alert
    :pulse))

(defmulti ^:private should-send-notification?
  "Returns true if given the pulse type and resultset a new notification (pulse or alert) should be sent"
  (fn [pulse _results] (alert-or-pulse pulse)))

(defmethod should-send-notification? :alert
  [{:keys [alert_condition] :as alert} results]
  (cond
    (= "rows" alert_condition)
    (not (are-all-cards-empty? results))

    (= "goal" alert_condition)
    (goal-met? alert results)

    :else
    (let [^String error-text (tru "Unrecognized alert with condition ''{0}''" alert_condition)]
      (throw (IllegalArgumentException. error-text)))))

(defmethod should-send-notification? :pulse
  [{:keys [alert_condition] :as pulse} results]
  (if (:skip_if_empty pulse)
    (not (are-all-cards-empty? results))
    true))

;; 'notification' used below means a map that has information needed to send a Pulse/Alert, including results of
;; running the underlying query

(defmulti ^:private notification
  "Polymorphoic function for creating notifications. This logic is different for pulse type (i.e. alert vs. pulse) and
  channel_type (i.e. email vs. slack vs. telegram vs. webhook)"
  {:arglists '([alert-or-pulse results channel])}
  (fn [pulse _ {:keys [channel_type]}]
    [(alert-or-pulse pulse) (keyword channel_type)]))

(defmethod notification [:pulse :email]
  [{pulse-id :id, pulse-name :name, dashboard-id :dashboard_id, :as pulse} results {:keys [recipients]}]
  (log/debug (u/format-color 'cyan (trs "Sending Pulse ({0}: {1}) with {2} Cards via email"
                                        pulse-id (pr-str pulse-name) (count results))))
  (let [email-recipients (filterv u/email? (map :email recipients))
        query-results    (filter :card results)
        timezone         (-> query-results first :card defaulted-timezone)
        dashboard        (Dashboard :id dashboard-id)]
    {:channel-type :email
     :subject      (subject pulse)
     :recipients   email-recipients
     :message-type :attachments
     :message      (messages/render-pulse-email timezone pulse dashboard results)}))

(defmethod notification [:pulse :slack]
  [{pulse-id :id, pulse-name :name, dashboard-id :dashboard_id, :as pulse}
   results
   {{channel-id :channel} :details}]
  (log/debug (u/format-color 'cyan (trs "Sending Pulse ({0}: {1}) with {2} Cards via Slack"
                                        pulse-id (pr-str pulse-name) (count results))))
  (let [dashboard (Dashboard :id dashboard-id)]
    {:channel-type :slack
     :channel-id  channel-id
     :attachments (remove nil?
                          (flatten [(slack-dashboard-header pulse dashboard)
                                    (create-slack-attachment-data results)
                                    (when dashboard (slack-dashboard-footer pulse dashboard))]))}))

(defmethod notification [:pulse :telegram]
           [{pulse-id :id, pulse-name :name, dashboard-id :dashboard_id, :as pulse}
            results
            {{chat-id :chat-id} :details}]
           (log/debug (u/format-color 'cyan (trs "Sending Pulse ({0}: {1}) with {2} Cards via Telegram"
                                                 pulse-id (pr-str pulse-name) (count results))))
           (let [dashboard (Dashboard :id dashboard-id)]
             {:channel-type :telegram :chat-id  chat-id :attachments results :pulse pulse :dashboard dashboard}))

(defmethod notification [:pulse :webhook]
           [{pulse-id :id, pulse-name :name, dashboard-id :dashboard_id, :as pulse}
            results
            details]
           (log/debug (u/format-color 'cyan (trs "Sending Pulse ({0}: {1}) with {2} Cards via Webhook"
                                                 pulse-id (pr-str pulse-name) (count results))))
           (let [dashboard (Dashboard :id dashboard-id)]
             {:channel-type :webhook :attachments results :pulse pulse :dashboard dashboard}))

(defmethod notification [:alert :email]
  [{:keys [id] :as pulse} results channel]
  (log/debug (trs "Sending Alert ({0}: {1}) via email" id name))
  (let [condition-kwd    (messages/pulse->alert-condition-kwd pulse)
        email-subject    (trs "Alert: {0} has {1}"
                              (first-question-name pulse)
                              (alert-condition-type->description condition-kwd))
        email-recipients (filterv u/email? (map :email (:recipients channel)))
        first-result     (first results)
        timezone         (-> first-result :card defaulted-timezone)]
    {:channel-type :email
     :subject      email-subject
     :recipients   email-recipients
     :message-type :attachments
     :message      (messages/render-alert-email timezone pulse channel results (ui/find-goal-value first-result))}))

(defmethod notification [:alert :slack]
  [pulse results {{channel-id :channel} :details}]
  (log/debug (u/format-color 'cyan (trs "Sending Alert ({0}: {1}) via Slack" (:id pulse) (:name pulse))))
  {:channel-type :slack
   :channel-id  channel-id
   :attachments (cons {:blocks [{:type "header"
                                 :text {:type "plain_text"
                                        :text (str "🔔 " (first-question-name pulse))
                                        :emoji true}}]}
                      (create-slack-attachment-data results))})

(defmethod notification [:alert :telegram]
  [pulse results {{chat-id :chat-id} :details}]
  (log/debug (u/format-color 'cyan (trs "Sending Alert ({0}: {1}) via Telegram" (:id pulse) (:name pulse))))
  {:channel-type :telegram
   :chat-id  chat-id
   :message (str "🔔 " (first-question-name pulse))
   :attachments results})

(defmethod notification [:alert :webhook]
  [pulse results details]
  (log/debug (u/format-color 'cyan (trs "Sending Alert ({0}: {1}) via Webhook" (:id pulse) (:name pulse))))
  {:channel-type :webhook
   :message (str "🔔 " (first-question-name pulse))
   :attachments results})

(defmethod notification :default
  [_ _ {:keys [channel_type]}]
  (throw (UnsupportedOperationException. (tru "Unrecognized channel type {0}" (pr-str channel_type)))))

(defn- results->notifications [{:keys [channels channel-ids], pulse-id :id, :as pulse} results]
  (let [channel-ids (or channel-ids (mapv :id channels))]
    (when (should-send-notification? pulse results)
      (when (:alert_first_only pulse)
        (db/delete! Pulse :id pulse-id))
      ;; `channel-ids` is the set of channels to send to now, so only send to those. Note the whole set of channels
      (for [channel channels
            :when   (contains? (set channel-ids) (:id channel))]
        (notification pulse results channel)))))

(defn- pulse->notifications
  "Execute the underlying queries for a sequence of Pulses and return the results as 'notification' maps."
  [{:keys [cards], pulse-id :id, :as pulse} dashboard]
  (results->notifications pulse
                          (if dashboard
                            ;; send the dashboard
                            (execute-dashboard pulse dashboard)
                            ;; send the cards instead
                            (for [card  cards
                                  ;; Pulse ID may be `nil` if the Pulse isn't saved yet
                                  :let  [result (pu/execute-card pulse (u/the-id card), :pulse-id pulse-id)]
                                  ;; some cards may return empty results, e.g. if the card has been archived
                                  :when result]
                              result))))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                             Sending Notifications                                              |
;;; +----------------------------------------------------------------------------------------------------------------+

(defmulti ^:private send-notification!
  "Invokes the side-effecty function for sending emails/slacks/telegrams/webhook events depending on the notification type"
  {:arglists '([pulse-or-alert])}
  (fn [{:keys [channel-type], :as details}]
    (case channel-type
      :slack :slack
      :telegram :telegram
      :webhook :webhook
      :email)))

(defmethod send-notification! :slack
  [{:keys [channel-id message attachments]}]
  (let [attachments (create-and-upload-slack-attachments! attachments)]
    (slack/post-chat-message! channel-id message attachments)))

(defmethod send-notification! :telegram
  [{:keys [chat-id attachments pulse dashboard]}]
  (post-telegram-message! attachments chat-id pulse dashboard))

(defmethod send-notification! :webhook
  [{:keys [ds-webhook-url attachments pulse dashboard]}]
  (send-webhook-event! attachments pulse dashboard))

(defmethod send-notification! :email
  [{:keys [subject recipients message-type message]}]
  (email/send-message!
    :subject      subject
    :recipients   recipients
    :message-type message-type
    :message      message))

(defn- send-notifications! [notifications]
  (doseq [notification notifications]
    ;; do a try-catch around each notification so if one fails, we'll still send the other ones for example, an Alert
    ;; set up to send over both Slack & email: if Slack fails, we still want to send the email (#7409)
    (try
      (send-notification! notification)
      (catch Throwable e
        (log/error e (trs "Error sending notification!"))))))

(defn send-pulse!
  "Execute and Send a `Pulse`, optionally specifying the specific `PulseChannels`.  This includes running each
   `PulseCard`, formatting the results, and sending the results to any specified destination.

  `channel-ids` is the set of channel IDs to send to *now* -- this may be a subset of the full set of channels for
  the Pulse.

   Example:
       (send-pulse! pulse)                       Send to all Channels
       (send-pulse! pulse :channel-ids [312])    Send only to Channel with :id = 312"
  [{:keys [cards dashboard_id], :as pulse} & {:keys [channel-ids]}]
  {:pre [(map? pulse) (integer? (:creator_id pulse))]}
  (let [dashboard (Dashboard :id dashboard_id)
        pulse     (-> pulse
                      pulse/map->PulseInstance
                      ;; This is usually already done by this step, in the `send-pulses` task which uses `retrieve-pulse`
                      ;; to fetch the Pulse.
                      pulse/hydrate-notification
                      (merge (when channel-ids {:channel-ids channel-ids})))]
    (when (not (:archived dashboard))
      (send-notifications! (pulse->notifications pulse dashboard)))))
