package org.openstreetmap.josm.plugins.livetouched;

import org.openstreetmap.josm.spi.preferences.Config;

/**
 * Backend settings. Defaults point to production; the advanced preferences
 * live-touched.base-url / .api-key / .origin allow testing against a local key-value-db.
 */
final class Settings {
    static final String PRIVACY_URL = "https://github.com/osm-editor-kit/osm-live-touched/blob/main/PRIVACY.md";
    /** Bump when the consent text or privacy statement changes; users are asked again. */
    static final int CONSENT_VERSION = 1;
    static final String PREF_CONSENT = "live-touched.consent-version";
    static final String PREF_ENABLED = "live-touched.enabled";

    private Settings() {
    }

    static String baseUrl() {
        return Config.getPref().get("live-touched.base-url", "https://key-value-store.fixmycity.workers.dev");
    }

    static String project() {
        return Config.getPref().get("live-touched.project", "live-touched");
    }

    /** Public project key; it only selects the project. */
    static String apiKey() {
        return Config.getPref().get("live-touched.api-key", "kv_825ada0b89b3d63f541f5fd155dca104c2c0a229");
    }

    /** JOSM has no web origin; the project allowlist contains this fixed value. */
    static String origin() {
        return Config.getPref().get("live-touched.origin", "https://josm.openstreetmap.de");
    }

    static boolean hasConsent() {
        return Config.getPref().getInt(PREF_CONSENT, 0) >= CONSENT_VERSION;
    }

    static void storeConsent() {
        Config.getPref().putInt(PREF_CONSENT, CONSENT_VERSION);
    }
}
