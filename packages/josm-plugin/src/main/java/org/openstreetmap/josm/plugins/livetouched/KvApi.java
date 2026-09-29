package org.openstreetmap.josm.plugins.livetouched;

import java.io.IOException;
import java.io.StringReader;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Supplier;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;

/**
 * Minimal client for the key-value-db API (docs/api.md). Uses java.net.http because JOSM's own
 * HttpClient (HttpURLConnection) cannot send the Origin header the project allowlist requires.
 */
final class KvApi {
    static final class KvException extends Exception {
        private static final long serialVersionUID = 1L;
        final int status;
        final String code;

        KvException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }

    private final HttpClient http;
    private final String baseUrl;
    private final String project;
    private final String apiKey;
    private final String origin;
    private final String userAgent;
    private final String editor;
    private final Supplier<String> osmToken;
    /** Server time minus local time, from the Date header of the latest response. */
    private volatile long skewMs;

    KvApi(String baseUrl, String project, String apiKey, String origin, String userAgent, String editor,
            Supplier<String> osmToken) {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10));
        ProxySelector proxy = ProxySelector.getDefault(); // JOSM installs its own proxy selector
        if (proxy != null) builder.proxy(proxy);
        this.http = builder.build();
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.project = project;
        this.apiKey = apiKey;
        this.origin = origin;
        this.userAgent = userAgent;
        this.editor = editor;
        this.osmToken = osmToken;
    }

    long serverNow() {
        return System.currentTimeMillis() + skewMs;
    }

    private String url(String path) {
        return baseUrl + "/v1/projects/" + URLEncoder.encode(project, StandardCharsets.UTF_8) + "/" + path;
    }

    private JsonObject send(String method, String path, JsonObject body) throws KvException {
        String token = osmToken.get();
        if (token == null) {
            throw new KvException(401, "no_osm_login", "Not logged in to OpenStreetMap in JOSM");
        }
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url(path)))
                .timeout(Duration.ofSeconds(20))
                .header("X-Api-Key", apiKey)
                .header("Origin", origin)
                .header("User-Agent", userAgent)
                .header("Authorization", "Bearer " + token);
        if (body != null) {
            req.header("Content-Type", "application/json");
            req.method(method, HttpRequest.BodyPublishers.ofString(body.toString()));
        } else {
            req.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> res;
        try {
            res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new KvException(0, "network", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KvException(0, "interrupted", "Interrupted");
        }
        res.headers().firstValue("Date").ifPresent(d -> {
            try {
                long server = ZonedDateTime.parse(d, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
                skewMs = server - System.currentTimeMillis();
            } catch (RuntimeException ignored) {
                // keep the previous skew
            }
        });
        JsonObject json = null;
        String text = res.body();
        if (text != null && !text.isEmpty()) {
            try (JsonReader reader = Json.createReader(new StringReader(text))) {
                json = reader.readObject();
            } catch (RuntimeException e) {
                json = null;
            }
        }
        if (res.statusCode() >= 400) {
            String code = "internal";
            String message = "HTTP " + res.statusCode();
            if (json != null && json.containsKey("error") && json.get("error") instanceof JsonObject) {
                JsonObject err = json.getJsonObject("error");
                code = err.getString("code", code);
                message = err.getString("message", message);
            }
            throw new KvException(res.statusCode(), code, message);
        }
        return json;
    }

    /** OSM user id of the logged-in user. */
    long me() throws KvException {
        JsonObject res = send("GET", "me", null);
        return res.getJsonObject("user").getJsonNumber("osm_uid").longValue();
    }

    /** Entries tagged with any of the tiles. */
    List<JsonObject> list(List<String> tags) throws KvException {
        StringBuilder q = new StringBuilder("entries?match=any&limit=500");
        for (String t : tags) q.append("&tag=").append(URLEncoder.encode(t, StandardCharsets.UTF_8));
        JsonObject res = send("GET", q.toString(), null);
        return res.getJsonArray("items").getValuesAs(JsonObject.class);
    }

    void batch(SyncState.Ops ops) throws KvException {
        JsonArrayBuilder puts = Json.createArrayBuilder();
        for (SyncState.Put p : ops.put) {
            JsonArrayBuilder bbox = Json.createArrayBuilder();
            for (double d : p.bbox) bbox.add(d);
            JsonObjectBuilder data = Json.createObjectBuilder()
                    .add("schema", 1)
                    .add("osm_type", p.type.apiName)
                    .add("osm_id", p.osmId)
                    .add("bbox", bbox)
                    .add("base_version", p.baseVersion)
                    .add("status", p.status)
                    .add("touched_at", p.touchedAt)
                    .add("editor", editor);
            if (p.newVersion != null) data.add("new_version", p.newVersion);
            if (p.changesetId != null) data.add("changeset_id", p.changesetId);
            JsonArrayBuilder tags = Json.createArrayBuilder();
            for (String t : p.tags) tags.add(t);
            puts.add(Json.createObjectBuilder().add("id", p.id).add("data", data).add("tags", tags));
        }
        JsonArrayBuilder deletes = Json.createArrayBuilder();
        for (String id : ops.delete) deletes.add(id);
        send("POST", "batch", Json.createObjectBuilder().add("put", puts).add("delete", deletes).build());
    }

    /** "Delete my data" in this project; returns the number of deleted entries. */
    int removeMine() throws KvException {
        return send("DELETE", "me/entries", null).getInt("deleted");
    }

    /** Drops the server-side token cache row. */
    void forget() throws KvException {
        send("DELETE", "me", null);
    }
}
