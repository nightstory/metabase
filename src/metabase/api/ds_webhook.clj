(ns metabase.api.ds_webhook
  "/api/ds-webhook endpoints"
  (:require [compojure.core :refer [PUT]]
            [schema.core :as s]
            [metabase.api.common :refer :all]
            [metabase.config :as config]
            [metabase.models.setting :as setting]
            [metabase.util.schema :as su]))

(defendpoint PUT "/settings"
  "Update Dashboard Subscription Webhook related settings. You must be a superuser to do this."
  [:as {{ds-webhook-url :ds-webhook-url, :as webhook-settings} :body}]
  {ds-webhook-url (s/maybe su/NonBlankString)}
  (check-superuser)
  (if-not ds-webhook-url
    (setting/set-many! {:ds-webhook-url nil})
    (setting/set-many! webhook-settings)))

(define-routes)
