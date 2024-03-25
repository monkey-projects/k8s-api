(ns kubernetes-api.swagger
  (:refer-clojure :exclude [read])
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.walk :as walk]
            [kubernetes-api.interceptors.auth :as interceptors.auth]
            [kubernetes-api.interceptors.raise :as interceptors.raise]
            [org.httpkit.client :as http]))

(def default-apis
  "Default apis for kubernetes"
  ["/api/"
   "/apis/"
   "/logs/"
   "/openid/"
   "/version/"])

(defn remove-watch-endpoints
  "Watch endpoints doesn't follow the http1.1 specification, so it will not work
  with httpkit or similar.

  Related: https://github.com/kubernetes/kubernetes/issues/50857"
  [swagger]
  (update swagger :paths
          (fn [paths]
            (apply dissoc (concat [paths]
                                  (filter (fn* [p1__944608#] (string/includes? p1__944608# "/watch")) (keys paths)))))))

(defn fix-k8s-verb
  "For some reason, the x-kubernetes-action given by the kubernetes api doesn't
  respect its own specification :shrug:

  More info: https://kubernetes.io/docs/reference/access-authn-authz/authorization/#determine-the-request-verb"
  [swagger]
  (walk/postwalk (fn [{:keys [x-kubernetes-action] :as form}]
                   (if x-kubernetes-action
                     (assoc form :x-kubernetes-action
                            (case (keyword x-kubernetes-action)
                              :post "create"
                              :put "update"
                              :watchlist "watch"
                              x-kubernetes-action))
                     form))
                 swagger))

(defn fix-consumes
  "Some endpoints declares that consumes */* which is not true and it doesn't
  let us select the correct encoder"
  [swagger]
  (walk/postwalk (fn [{:keys [consumes] :as form}]
                   (if (= consumes ["*/*"])
                     (assoc form :consumes ["application/json"])
                     form))
                 swagger))

(defn add-summary [swagger]
  (walk/postwalk (fn [{:keys [description summary] :as form}]
                   (if description
                     (assoc form :summary (or summary description))
                     form))
                 swagger))

(def arbitrary-api-resources-route
  {"/apis/{api}/{version}/"
   {:get        {:consumes    ["application/json"
                               "application/yaml"
                               "application/vnd.kubernetes.protobuf"]
                 :summary     "get available resources for arbitrary api"
                 :operationId "GetArbitraryAPIResources"
                 :produces    ["application/json"
                               "application/yaml"
                               "application/vnd.kubernetes.protobuf"]
                 :responses   {"200" {:description "OK"
                                      :schema      {:$ref "#/definitions/io.k8s.apimachinery.pkg.apis.meta.v1.APIResourceList"}}
                               "401" {:description "Unauthorized"}}
                 :schemes     ["https"]}
    :parameters [{:in     "path"
                  :name   "api"
                  :schema {:type "string"}}
                 {:in     "path"
                  :name   "version"
                  :schema {:type "string"}}]}})

(defn add-some-routes
  [swagger new-definitions new-routes]
  (-> swagger
      (update :paths #(merge % new-routes))
      (update :definitions #(merge % new-definitions))))

(defn replace-swagger-body-schema [params new-body-schema]
  (mapv (fn [param]
          (if (= "body" (:name param))
            (assoc param :schema new-body-schema)
            param))
        params))

(def rfc6902-json-schema
  {:type "array"
   :items {:type "object"
           :required [:op :path :value]
           :properties {:op {:type "string"
                             :enum ["add" "remove" "replace" "move" "test"]}
                        :path {:type "string"}
                        :value {}}}})

(defn patch-operations [operation update-schema]
  (letfn [(custom-operation [{:keys [schema content-type action summary route-name-suffix]}]
            (-> operation
                (update :parameters #(replace-swagger-body-schema % schema))
                (update :operationId #(str % route-name-suffix))
                (assoc :consumes [content-type])
                (assoc :summary (format (or summary (:summary operation)) (-> operation :x-kubernetes-group-version-kind :kind)))
                (assoc :x-kubernetes-action action)))]
    {:patch/json (custom-operation {:schema rfc6902-json-schema
                                    :content-type "application/json-patch+json"
                                    :summary "update the specified %s using RFC6902"
                                    :route-name-suffix "JsonPatch"
                                    :action "patch/json"})
     :patch/strategic (custom-operation {:schema update-schema
                                         :content-type "application/strategic-merge-patch+json"
                                         :summary "update the specified %s using a smart strategy"
                                         :route-name-suffix "StrategicMerge"
                                         :action "patch/strategic"})
     :patch/json-merge (custom-operation {:schema update-schema
                                          :content-type "application/merge-patch+json"
                                          :summary "update the specified %s using RFC7286"
                                          :route-name-suffix "JsonMerge"
                                          :action "patch/json-merge"})
     :apply/server (custom-operation {:schema update-schema
                                      :content-type "application/apply-patch+yaml"
                                      :summary "create or update the specified %s using server side apply"
                                      :route-name-suffix "ApplyServerSide"
                                      :action "apply/server"})}))

(defn update-path-item [swagger update-fn]
  (update swagger :paths
          (fn [paths]
            (into {} (map (fn [[path item]] [path (update-fn path item)]) paths)))))

(defn find-update-body-schema [swagger path]
  (->> (select-keys (get-in swagger [:paths path]) [:put :post])
       vals
       (filter (fn [{:keys [x-kubernetes-action]}] (= x-kubernetes-action "update")))
       (mapcat :parameters)
       (filter (fn [param] (= (:name param) "body")))
       (map :schema)
       first))

(defn add-patch-routes [swagger]
  (-> swagger
      (update-path-item (fn [path item]
                          (->> item
                               (mapcat (fn [[verb operation]]
                                         (if (= verb :patch)
                                           (patch-operations operation (find-update-body-schema swagger path))
                                           [[verb operation]])))
                               (into {}))))))

(defn default-paths [paths]
  {"/apis/" (get-in paths ["/apis/"])
   "/api/" (get-in paths ["/api/"])})

(defn filter-api
  "Returns the paths that start with the given api and version
   e.g. /apis/{api}/...; /api/{api}/...; /{api}/..."
  [api paths]
  (let [api-paths [(str "/apis/" api)
                   (str "/api/" api)
                   (str "/" api)
                   api]
        starts-with-api? (fn [path]
                           (some #(string/starts-with? path %) api-paths))]
    (into {} (filter (comp starts-with-api? first) paths))))

(defn from-apis
  "Returns the default paths and the paths for the given apis"
  [apis paths]
  (let [api-paths (mapv #(filter-api % paths) apis)]
    (into (default-paths paths)
          api-paths)))

(defn filter-paths
  "Returns an updated schema with the paths for the given apis"
  [schema apis]
  (update schema :paths (partial from-apis apis)))

(defn ^:private customized
  "Receives a kubernetes swagger, adds a description to the routes and some
  generic routes"
  [swagger {{:keys [apis] :or {apis default-apis}} :openapi}]
  (-> swagger
      (filter-paths apis)
      add-summary
      (add-some-routes {} arbitrary-api-resources-route)
      fix-k8s-verb
      fix-consumes
      remove-watch-endpoints
      add-patch-routes))

(defn ^:private keyword-except-paths [s]
  (if (string/starts-with? s "/")
    s
    (keyword s)))

(defn openapi-discovery-enabled?
  [opts]
  (not= :disabled (get-in opts [:openapi :discovery])))

(defn parse-swagger-file
  "Reads a swagger file and returns a clojure map with the swagger data"
  [file]
  (-> (io/resource file)
      io/input-stream
      slurp
      (json/parse-string keyword-except-paths)))

(defn read [opts]
  (customized (parse-swagger-file "kubernetes_api/swagger.json") opts))

(defn from-api* [api-root opts]
  (json/parse-string
   (interceptors.raise/check-response
    @(http/request (merge {:url    (str api-root "/openapi/v2")
                           :method :get}
                          (interceptors.auth/request-auth-params opts))))
   keyword-except-paths))

(defn from-api [api-root opts]
  (try
    (when (openapi-discovery-enabled? opts)
      (customized (from-api* api-root opts) opts))
    (catch Exception _
      nil)))
