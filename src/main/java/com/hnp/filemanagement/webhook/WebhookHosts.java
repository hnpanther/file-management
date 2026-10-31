package com.hnp.filemanagement.webhook;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * Whether an address is one a subscription may call (roadmap 13.18): its host is on
 * {@code filemanagement.webhooks.allowed-hosts}. Asked when a subscription is saved and again before every send - a
 * host taken off the list is called no more, whatever was saved.
 *
 * <p>An entry is {@code host} (any port), {@code host:port}, or {@code *.domain} (any host below the domain, not the
 * domain itself). Compared without case; an IPv6 address is written in brackets, as in a URL ({@code [::1]:8080}).
 * Plain {@code http} is allowed as well as {@code https} - the owner's choice; the body is signed either way.
 */
public final class WebhookHosts {

    private WebhookHosts() {
    }

    /** Whether this URL is one a subscription may have: http or https, a host, no user in it, and allowed. */
    public static boolean allowed(URI uri, List<String> allowedHosts) {
        if (uri == null || uri.getScheme() == null || uri.getHost() == null || uri.getRawUserInfo() != null) {
            return false;
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return false;
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort() != -1 ? uri.getPort() : scheme.equals("https") ? 443 : 80;
        for (String entry : allowedHosts) {
            if (matches(entry.toLowerCase(Locale.ROOT), host, port)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(String entry, String host, int port) {
        String entryHost = entry;
        Integer entryPort = null;
        int colon = entry.lastIndexOf(':');
        if (colon > 0 && !entry.endsWith("]") && (entry.startsWith("[") || entry.indexOf(':') == colon)) {
            try {
                entryPort = Integer.parseInt(entry.substring(colon + 1));
                entryHost = entry.substring(0, colon);
            } catch (NumberFormatException e) {
                return false;
            }
        }
        if (entryPort != null && entryPort != port) {
            return false;
        }
        if (entryHost.startsWith("*.")) {
            String domain = entryHost.substring(1);
            return host.endsWith(domain) && host.length() > domain.length();
        }
        return entryHost.equals(host);
    }
}
