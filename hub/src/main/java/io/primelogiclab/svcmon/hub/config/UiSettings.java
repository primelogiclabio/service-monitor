package io.primelogiclab.svcmon.hub.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import tools.jackson.databind.json.JsonMapper;

/**
 * The dashboard's config.json for a dashboard that comes from inside the jar, built from the hub's own settings
 * (svcmon.ui.proxy.*) so the whole deployment is configured in one file. It follows the dashboard's own rules for
 * that file: a wrong value stops the hub from starting, so a typo is found at once and not by a page that does not
 * sign anyone in.
 */
final class UiSettings {

    private static final Map<String, String> DEFAULT_HEADERS = Map.of("X-Web-Client", "1");
    private static final Pattern PATH = Pattern.compile("^/(?!/).*");
    private static final Pattern ADDRESS = Pattern.compile("^https?://[^\\s/].*");
    private static final Pattern HEADER_NAME = Pattern.compile("^[A-Za-z0-9-]+$");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private UiSettings() {
    }

    /** The JSON text for the page, or an exception naming the setting that is wrong. */
    static String json(HubProperties.Ui ui) {
        HubProperties.Proxy p = ui == null || ui.proxy() == null ? HubProperties.Proxy.DEFAULT : ui.proxy();
        Map<String, Object> out = new LinkedHashMap<>();
        if (!p.enabled()) {
            out.put("proxy", false);
            return JSON.writeValueAsString(out);
        }
        String apiBase = p.apiBase() == null ? "" : p.apiBase().replaceAll("/+$", "");
        if (!PATH.matcher(apiBase).matches()) {
            throw bad("svcmon.ui.proxy.api-base", "a path on this site, starting with a single /");
        }
        Map<String, String> headers = p.headers() == null || p.headers().isEmpty() ? DEFAULT_HEADERS : p.headers();
        for (Map.Entry<String, String> h : headers.entrySet()) {
            if (!HEADER_NAME.matcher(h.getKey()).matches() || h.getValue() == null) {
                throw bad("svcmon.ui.proxy.headers", "header names (letters, digits and -) with a value; '" + h.getKey() + "' is not");
            }
            if (h.getKey().equalsIgnoreCase("authorization")) {
                throw bad("svcmon.ui.proxy.headers", "free of Authorization, which the proxy adds");
            }
        }
        Map<String, Object> proxy = new LinkedHashMap<>();
        proxy.put("apiBase", apiBase);
        proxy.put("headers", new LinkedHashMap<>(headers));
        proxy.put("signInUrl", address("svcmon.ui.proxy.sign-in-url", p.signInUrl()));
        proxy.put("signOutUrl", address("svcmon.ui.proxy.sign-out-url", p.signOutUrl()));
        out.put("proxy", proxy);
        return JSON.writeValueAsString(out);
    }

    /** A path on this site or an http(s) address, never a script or data address, since the page navigates to it. */
    private static String address(String setting, String value) {
        if (value == null || !(PATH.matcher(value).matches() || ADDRESS.matcher(value).matches())) {
            throw bad(setting, "a path starting with / or an http(s) address");
        }
        return value;
    }

    private static IllegalStateException bad(String setting, String wanted) {
        return new IllegalStateException(setting + " must be " + wanted);
    }
}
