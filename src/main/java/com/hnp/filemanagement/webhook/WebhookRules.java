package com.hnp.filemanagement.webhook;

import com.hnp.filemanagement.folder.domain.Bin;
import com.hnp.filemanagement.file.domain.FileEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The subscriptions as the matching reads them (roadmap 13.18): every subscription, parsed once into a {@link Rule},
 * held in memory and replaced whole when one changes ({@link WebhookRuleCache}). An event is matched against them
 * with no statement: its folder's path names every folder above it, its class path every class above it.
 *
 * <p>Immutable: a snapshot a thread reads while another replaces it.
 */
public final class WebhookRules {

    /** A place an event happened: its folder - id, path, class path. */
    public record Place(int folderId, String path, String classPath) {
    }

    /**
     * One event as matched: what happened, where (and, for a move, where from), by whom, to which file - its name,
     * and its extensions, asked only of a rule that filters by them.
     */
    public record Occurrence(FileEvent event, Place place, Place movedFrom, Integer apiKeyId, String fileName,
                             Supplier<Set<String>> extensions) {
    }

    /** One subscription, parsed. */
    public record Rule(int webhookId, String name, WebhookScope scope, Integer folderId, Integer classId,
                       Set<WebhookEvent> events, Set<String> extensions, Pattern namePattern, WebhookActors actors,
                       Set<Integer> excludedKeyIds, boolean enabled, int ratePerMinute, int concurrency) {

        /** Whether this subscription is sent the occurrence. */
        public boolean matches(Occurrence occurrence) {
            Optional<WebhookEvent> event = WebhookEvent.of(occurrence.event());
            if (event.isEmpty() || !events.contains(event.get())) {
                return false;
            }
            Integer key = occurrence.apiKeyId();
            if ((actors == WebhookActors.PEOPLE && key != null) || (actors == WebhookActors.KEYS && key == null)) {
                return false;
            }
            if (key != null && excludedKeyIds.contains(key)) {
                return false;
            }
            if (!covers(occurrence.place()) && (occurrence.movedFrom() == null || !covers(occurrence.movedFrom()))) {
                return false;
            }
            if (namePattern != null || !extensions.isEmpty()) {
                Set<String> of = occurrence.extensions().get();
                if (!extensions.isEmpty() && of.stream().noneMatch(extensions::contains)) {
                    return false;
                }
                if (namePattern != null && of.stream().noneMatch(
                        extension -> namePattern.matcher(occurrence.fileName() + "." + extension).matches())) {
                    return false;
                }
            }
            return true;
        }

        /** Whether the place is where this subscription listens - never in the recycle bin. */
        boolean covers(Place place) {
            if (place == null || Bin.holds(place.path())) {
                return false;
            }
            // A materialised path names every folder above, itself included, each between slashes: no parsing.
            return switch (scope) {
                case FOLDER -> folderId != null && folderId == place.folderId();
                case SUBTREE -> folderId != null && place.path().contains("/" + folderId + "/");
                case CLASS -> classId != null && place.classPath() != null
                        && place.classPath().contains("/" + classId + "/")
                        && (folderId == null || place.path().contains("/" + folderId + "/"));
            };
        }
    }

    private final List<Rule> rules;

    public WebhookRules(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    public static WebhookRules none() {
        return new WebhookRules(List.of());
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    public List<Rule> all() {
        return rules;
    }

    /** The subscriptions the occurrence is sent to - paused ones too: their deliveries wait. */
    public List<Rule> matching(Occurrence occurrence) {
        List<Rule> matched = new ArrayList<>();
        for (Rule rule : rules) {
            if (rule.matches(occurrence)) {
                matched.add(rule);
            }
        }
        return matched;
    }

    // ------------------------------------------------------------------ parsing a subscription

    /** {@code FILE_CREATED,MOVED} as a set; a name no longer an event is left out. */
    public static Set<WebhookEvent> events(String stored) {
        Set<WebhookEvent> events = EnumSet.noneOf(WebhookEvent.class);
        for (String each : split(stored)) {
            Arrays.stream(WebhookEvent.values()).filter(event -> event.name().equals(each)).forEach(events::add);
        }
        return events;
    }

    /** {@code pdf,docx} as a set, lower case, without a dot. */
    public static Set<String> extensions(String stored) {
        return split(stored).stream().map(each -> each.toLowerCase(Locale.ROOT).replaceFirst("^\\.", ""))
                .collect(Collectors.toUnmodifiableSet());
    }

    /** {@code 12,40} as a set of ids; anything not a number left out. */
    public static Set<Integer> ids(String stored) {
        Set<Integer> ids = new HashSet<>();
        for (String each : split(stored)) {
            try {
                ids.add(Integer.parseInt(each));
            } catch (NumberFormatException ignored) {
                // not an id: written by nobody but a person in the database
            }
        }
        return Set.copyOf(ids);
    }

    /**
     * A glob - {@code *} any run of characters, {@code ?} one - as a pattern on the whole name, without case; null for
     * none. Every other character stands for itself.
     */
    public static Pattern glob(String glob) {
        if (glob == null || glob.isBlank()) {
            return null;
        }
        StringBuilder regex = new StringBuilder();
        for (char c : glob.strip().toCharArray()) {
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);
    }

    static List<String> split(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        return Arrays.stream(stored.split(",")).map(String::strip).filter(each -> !each.isEmpty()).toList();
    }

    /** The ids a materialised path is made of, outermost - the root - first. */
    static List<Integer> idsInOrder(String path) {
        List<Integer> ids = new ArrayList<>();
        for (String segment : path.split("/")) {
            if (!segment.isBlank()) {
                ids.add(Integer.parseInt(segment));
            }
        }
        return ids;
    }
}
