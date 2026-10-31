package com.hnp.filemanagement.webhook;

import com.hnp.filemanagement.folder.persistence.FolderClassRepository;
import com.hnp.filemanagement.identity.persistence.ApiKeyRepository;
import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.shared.exception.DuplicateResourceException;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.shared.exception.ResourceNotFoundException;
import com.hnp.filemanagement.shared.web.GlobalGeneralLogging;
import com.hnp.filemanagement.shared.web.UiMessages;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Webhooks (roadmap 13.18, 2.19.0): the subscriptions - each with where it listens, what it is sent, whether it
 * sends and what waits - the form that makes or changes one, its deliveries, and the buttons: a new secret, pause,
 * resume, a test event, send again, delete. A secret is shown once, on the page the request that made it renders -
 * never in a redirect, a flash attribute or a URL - and never logged.
 */
@Controller
@RequestMapping("/settings/webhooks")
public class WebhookController {

    private static final String VIEW = "settings/webhooks.html";
    private static final String SECRET_VIEW = "settings/webhook-secret.html";
    private static final String DELIVERIES_VIEW = "settings/webhook-deliveries.html";
    private static final String HOME = "redirect:/settings/webhooks";
    private static final int DELIVERIES_PAGE = 50;

    private final WebhookService webhookService;
    private final WebhookDispatcher dispatcher;
    private final WebhookProperties properties;
    private final WebhookSecrets secrets;
    private final ApiKeyRepository apiKeyRepository;
    private final FolderClassRepository folderClassRepository;
    private final GlobalGeneralLogging globalGeneralLogging;
    private final UiMessages messages;

    public WebhookController(WebhookService webhookService, WebhookDispatcher dispatcher, WebhookProperties properties,
                             WebhookSecrets secrets, ApiKeyRepository apiKeyRepository,
                             FolderClassRepository folderClassRepository,
                             GlobalGeneralLogging globalGeneralLogging, UiMessages messages) {
        this.webhookService = webhookService;
        this.dispatcher = dispatcher;
        this.properties = properties;
        this.secrets = secrets;
        this.apiKeyRepository = apiKeyRepository;
        this.folderClassRepository = folderClassRepository;
        this.globalGeneralLogging = globalGeneralLogging;
        this.messages = messages;
    }

    /** What the form shows: a subscription being made ({@code id} null) or changed. Never a secret. */
    public record Form(Integer id, String name, String url, String scope, Integer folderId, String folderTitle,
                       Integer classId, Set<WebhookEvent> events, String extensions, String namePattern, String actors,
                       Set<Integer> excludedKeyIds, String authKind, String authName, boolean hasAuthSecret,
                       int ratePerMinute, int concurrency, int pauseAfter) {
    }

    /** An API key as the exclusion list offers it. */
    public record KeyChoice(int id, String title) {
    }

    /** A folder class as the scope offers it. */
    public record ClassChoice(int id, String title) {
    }

    //WEBHOOKS_PAGE
    @PreAuthorize("hasAuthority('WEBHOOKS_PAGE') || hasAuthority('ADMIN')")
    @GetMapping
    public String list(Model model) {
        globalGeneralLogging.detail("webhooks page");
        fill(model, emptyForm());
        return VIEW;
    }

    //WEBHOOKS_PAGE
    @PreAuthorize("hasAuthority('WEBHOOKS_PAGE') || hasAuthority('ADMIN')")
    @GetMapping("/{id}")
    public String edit(@PathVariable("id") int id, Model model, RedirectAttributes redirect) {
        globalGeneralLogging.detail("webhook page, id=" + id);
        try {
            fill(model, formOf(webhookService.one(id)));
            return VIEW;
        } catch (ResourceNotFoundException e) {
            redirect.addFlashAttribute("refused", messages.get("webhook.notFound"));
            return HOME;
        }
    }

    //WEBHOOKS_PAGE
    @PreAuthorize("hasAuthority('WEBHOOKS_PAGE') || hasAuthority('ADMIN')")
    @GetMapping("/{id}/deliveries")
    public String deliveries(@PathVariable("id") int id, @RequestParam(value = "before", required = false) String before,
                             Model model, RedirectAttributes redirect) {
        globalGeneralLogging.detail("webhook deliveries page, id=" + id);
        try {
            WebhookService.View webhook = webhookService.one(id);
            List<WebhookDeliveryRepository.Row> rows = webhookService.deliveries(id, before, DELIVERIES_PAGE + 1);
            boolean hasNext = rows.size() > DELIVERIES_PAGE;
            List<WebhookDeliveryRepository.Row> page = hasNext ? rows.subList(0, DELIVERIES_PAGE) : rows;
            model.addAttribute("webhook", webhook);
            model.addAttribute("rows", page);
            model.addAttribute("next", hasNext ? page.getLast().cursor() : null);
            model.addAttribute("first", before == null || before.isBlank());
            return DELIVERIES_VIEW;
        } catch (ResourceNotFoundException e) {
            redirect.addFlashAttribute("refused", messages.get("webhook.notFound"));
            return HOME;
        }
    }

    //SAVE_WEBHOOK
    @PreAuthorize("hasAuthority('SAVE_WEBHOOK') || hasAuthority('ADMIN')")
    @PostMapping
    public String create(@AuthenticationPrincipal UserDetailsImpl userDetails,
                         @RequestParam(value = "name", required = false) String name,
                         @RequestParam(value = "url", required = false) String url,
                         @RequestParam(value = "scope", required = false) String scope,
                         @RequestParam(value = "folderId", required = false) String folderId,
                         @RequestParam(value = "classId", required = false) String classId,
                         @RequestParam(value = "event", required = false) List<String> events,
                         @RequestParam(value = "extensions", required = false) String extensions,
                         @RequestParam(value = "namePattern", required = false) String namePattern,
                         @RequestParam(value = "actors", required = false) String actors,
                         @RequestParam(value = "excludedKeyId", required = false) List<String> excludedKeyIds,
                         @RequestParam(value = "authKind", required = false) String authKind,
                         @RequestParam(value = "authName", required = false) String authName,
                         @RequestParam(value = "authSecret", required = false) String authSecret,
                         @RequestParam(value = "ratePerMinute", required = false) String ratePerMinute,
                         @RequestParam(value = "concurrency", required = false) String concurrency,
                         @RequestParam(value = "pauseAfter", required = false) String pauseAfter,
                         Model model) {
        globalGeneralLogging.detail("create webhook, name=" + name + ", scope=" + scope + ", folder id=" + folderId
                + ", class id=" + classId);
        WebhookService.Definition definition = definition(name, url, scope, folderId, classId, events, extensions,
                namePattern, actors, excludedKeyIds, authKind, authName, authSecret, ratePerMinute, concurrency, pauseAfter);
        try {
            WebhookService.WithSecret created = webhookService.create(definition, userDetails.getId());
            globalGeneralLogging.detail("webhook id=" + created.id() + " created");
            model.addAttribute("created", created);
            model.addAttribute("rotated", false);
            return SECRET_VIEW;
        } catch (InvalidDataException | DuplicateResourceException e) {
            globalGeneralLogging.detail(e.getClass().getSimpleName() + ":" + e.getMessage());
            model.addAttribute("refused", refusal(e));
            fill(model, formOf(null, definition, false));
            return VIEW;
        }
    }

    //SAVE_WEBHOOK
    @PreAuthorize("hasAuthority('SAVE_WEBHOOK') || hasAuthority('ADMIN')")
    @PostMapping("/{id}")
    public String update(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("id") int id,
                         @RequestParam(value = "name", required = false) String name,
                         @RequestParam(value = "url", required = false) String url,
                         @RequestParam(value = "scope", required = false) String scope,
                         @RequestParam(value = "folderId", required = false) String folderId,
                         @RequestParam(value = "classId", required = false) String classId,
                         @RequestParam(value = "event", required = false) List<String> events,
                         @RequestParam(value = "extensions", required = false) String extensions,
                         @RequestParam(value = "namePattern", required = false) String namePattern,
                         @RequestParam(value = "actors", required = false) String actors,
                         @RequestParam(value = "excludedKeyId", required = false) List<String> excludedKeyIds,
                         @RequestParam(value = "authKind", required = false) String authKind,
                         @RequestParam(value = "authName", required = false) String authName,
                         @RequestParam(value = "authSecret", required = false) String authSecret,
                         @RequestParam(value = "ratePerMinute", required = false) String ratePerMinute,
                         @RequestParam(value = "concurrency", required = false) String concurrency,
                         @RequestParam(value = "pauseAfter", required = false) String pauseAfter,
                         Model model, RedirectAttributes redirect) {
        globalGeneralLogging.detail("update webhook id=" + id + ", scope=" + scope + ", folder id=" + folderId
                + ", class id=" + classId);
        WebhookService.Definition definition = definition(name, url, scope, folderId, classId, events, extensions,
                namePattern, actors, excludedKeyIds, authKind, authName, authSecret, ratePerMinute, concurrency, pauseAfter);
        try {
            webhookService.update(id, definition, userDetails.getId());
            redirect.addFlashAttribute("saved", messages.get("webhook.saved", definition.name() == null ? "" : definition.name().strip()));
            return HOME;
        } catch (InvalidDataException | DuplicateResourceException | ResourceNotFoundException e) {
            globalGeneralLogging.detail(e.getClass().getSimpleName() + ":" + e.getMessage());
            if (e instanceof ResourceNotFoundException) {
                redirect.addFlashAttribute("refused", messages.get("webhook.notFound"));
                return HOME;
            }
            model.addAttribute("refused", refusal(e));
            boolean hadSecret = webhookService.one(id).hasAuthSecret();
            fill(model, formOf(id, definition, hadSecret));
            return VIEW;
        }
    }

    //SAVE_WEBHOOK
    @PreAuthorize("hasAuthority('SAVE_WEBHOOK') || hasAuthority('ADMIN')")
    @PostMapping("/{id}/rotate-secret")
    public String rotateSecret(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("id") int id,
                               Model model, RedirectAttributes redirect) {
        globalGeneralLogging.detail("rotate the secret of webhook id=" + id);
        try {
            model.addAttribute("created", webhookService.rotateSecret(id, userDetails.getId()));
            model.addAttribute("rotated", true);
            return SECRET_VIEW;
        } catch (InvalidDataException | ResourceNotFoundException e) {
            globalGeneralLogging.detail(e.getClass().getSimpleName() + ":" + e.getMessage());
            redirect.addFlashAttribute("refused", refusal(e));
            return HOME;
        }
    }

    //SAVE_WEBHOOK
    @PreAuthorize("hasAuthority('SAVE_WEBHOOK') || hasAuthority('ADMIN')")
    @PostMapping("/{id}/pause")
    public String pause(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("id") int id,
                        RedirectAttributes redirect) {
        globalGeneralLogging.detail("pause webhook id=" + id);
        return act(redirect, () -> webhookService.pause(id, userDetails.getId()), "webhook.paused");
    }

    //SAVE_WEBHOOK
    @PreAuthorize("hasAuthority('SAVE_WEBHOOK') || hasAuthority('ADMIN')")
    @PostMapping("/{id}/resume")
    public String resume(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("id") int id,
                         RedirectAttributes redirect) {
        globalGeneralLogging.detail("resume webhook id=" + id);
        return act(redirect, () -> webhookService.resume(id, userDetails.getId()), "webhook.resumed");
    }

    //DELETE_WEBHOOK
    @PreAuthorize("hasAuthority('DELETE_WEBHOOK') || hasAuthority('ADMIN')")
    @PostMapping("/{id}/delete")
    public String delete(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("id") int id,
                         RedirectAttributes redirect) {
        globalGeneralLogging.detail("delete webhook id=" + id);
        return act(redirect, () -> webhookService.delete(id, userDetails.getId()), "webhook.deleted");
    }

    //SAVE_WEBHOOK
    @PreAuthorize("hasAuthority('SAVE_WEBHOOK') || hasAuthority('ADMIN')")
    @PostMapping("/{id}/retry")
    public String retry(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("id") int id,
                        @RequestParam(value = "deliveryId", required = false) Long deliveryId,
                        RedirectAttributes redirect) {
        globalGeneralLogging.detail("retry webhook id=" + id + (deliveryId == null ? ", every failed delivery" : ", delivery id=" + deliveryId));
        try {
            int count = webhookService.retry(id, deliveryId, userDetails.getId());
            redirect.addFlashAttribute("saved", messages.get("webhook.retried", count));
        } catch (ResourceNotFoundException e) {
            redirect.addFlashAttribute("refused", messages.get("webhook.notFound"));
            return HOME;
        }
        return "redirect:/settings/webhooks/" + id + "/deliveries";
    }

    //SAVE_WEBHOOK
    @PreAuthorize("hasAuthority('SAVE_WEBHOOK') || hasAuthority('ADMIN')")
    @PostMapping("/{id}/test")
    public String test(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("id") int id,
                       RedirectAttributes redirect) {
        globalGeneralLogging.detail("test event to webhook id=" + id);
        try {
            WebhookService.TestEvent test = webhookService.queueTest(id, userDetails.getUsername(), userDetails.getId());
            WebhookSender.Outcome outcome = dispatcher.sendTest(test.webhook(), test.delivery());
            globalGeneralLogging.detail("test event to webhook id=" + id + ": " + (outcome.delivered() ? "delivered" : "failed")
                    + ", status " + outcome.status());
            if (outcome.delivered()) {
                redirect.addFlashAttribute("saved", messages.get("webhook.test.delivered", outcome.status()));
            } else {
                redirect.addFlashAttribute("refused", messages.get("webhook.test.failed",
                        outcome.status() == null ? "-" : outcome.status(), outcome.error() == null ? "" : outcome.error()));
            }
        } catch (InvalidDataException | ResourceNotFoundException e) {
            globalGeneralLogging.detail(e.getClass().getSimpleName() + ":" + e.getMessage());
            redirect.addFlashAttribute("refused", refusal(e));
        }
        return HOME;
    }

    // ------------------------------------------------------------------ pieces

    private String act(RedirectAttributes redirect, Runnable action, String done) {
        try {
            action.run();
            redirect.addFlashAttribute("saved", messages.get(done));
        } catch (ResourceNotFoundException e) {
            redirect.addFlashAttribute("refused", messages.get("webhook.notFound"));
        }
        return HOME;
    }

    private String refusal(RuntimeException e) {
        if (e instanceof InvalidDataException invalid && invalid.getMessageCode().isPresent()) {
            return messages.get(invalid.getMessageCode().get(), invalid.getMessageArguments());
        }
        if (e instanceof DuplicateResourceException) {
            return messages.get("webhook.invalid.nameTaken");
        }
        if (e instanceof ResourceNotFoundException) {
            return messages.get("webhook.notFound");
        }
        return messages.get("webhook.invalid");
    }

    private static WebhookService.Definition definition(String name, String url, String scope, String folderId,
                                                        String classId, List<String> events, String extensions,
                                                        String namePattern, String actors, List<String> excludedKeyIds,
                                                        String authKind, String authName, String authSecret,
                                                        String ratePerMinute, String concurrency, String pauseAfter) {
        return new WebhookService.Definition(name, url, enumOf(WebhookScope.class, scope, WebhookScope.SUBTREE),
                number(folderId), number(classId), eventsOf(events), extensions, namePattern,
                enumOf(WebhookActors.class, actors, WebhookActors.ALL),
                excludedKeyIds == null ? "" : String.join(",", excludedKeyIds), authKind, authName, authSecret,
                limit(ratePerMinute), limit(concurrency), limit(pauseAfter));
    }

    private static Set<WebhookEvent> eventsOf(List<String> names) {
        Set<WebhookEvent> events = EnumSet.noneOf(WebhookEvent.class);
        if (names != null) {
            for (String name : names) {
                for (WebhookEvent event : WebhookEvent.values()) {
                    if (event.name().equals(name)) {
                        events.add(event);
                    }
                }
            }
        }
        return events;
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String name, E otherwise) {
        if (name == null || name.isBlank()) {
            return otherwise;
        }
        for (E each : type.getEnumConstants()) {
            if (each.name().equals(name.strip())) {
                return each;
            }
        }
        return otherwise;
    }

    private static Integer number(String value) {
        try {
            return value == null || value.isBlank() ? null : Integer.valueOf(value.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A pace or a count as typed; a value that is not a number is one out of range, so it is refused, not defaulted. */
    private static Integer limit(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.strip());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private Form emptyForm() {
        return new Form(null, "", "", WebhookScope.SUBTREE.name(), null, null, null,
                EnumSet.of(WebhookEvent.FILE_CREATED), "", "", WebhookActors.ALL.name(), Set.of(), "NONE", "", false,
                properties.ratePerMinute(), properties.concurrency(), properties.pauseAfter());
    }

    private static Form formOf(WebhookService.View v) {
        return new Form(v.id(), v.name(), v.url(), v.scope().name(), v.folderId(), v.folderTitle(), v.classId(),
                v.events(), v.extensions(), v.namePattern(), v.actors().name(), WebhookRules.ids(v.excludedKeyIds()),
                v.authKind(), v.authName() == null ? "" : v.authName(), v.hasAuthSecret(), v.ratePerMinute(),
                v.concurrency(), v.pauseAfter());
    }

    /** The form as it was posted, to type again what was refused - its credential never sent back. */
    private Form formOf(Integer id, WebhookService.Definition d, boolean hasAuthSecret) {
        return new Form(id, d.name(), d.url(), d.scope().name(), d.folderId(), null, d.classId(), d.events(),
                d.extensions(), d.namePattern(), d.actors().name(), WebhookRules.ids(d.excludedKeyIds()),
                d.authKind() == null ? "NONE" : d.authKind(), d.authName() == null ? "" : d.authName(), hasAuthSecret,
                d.ratePerMinute() == null ? properties.ratePerMinute() : Math.max(d.ratePerMinute(), 0),
                d.concurrency() == null ? properties.concurrency() : Math.max(d.concurrency(), 0),
                d.pauseAfter() == null ? properties.pauseAfter() : Math.max(d.pauseAfter(), 0));
    }

    private void fill(Model model, Form form) {
        model.addAttribute("form", form);
        model.addAttribute("webhooks", webhookService.all());
        model.addAttribute("events", WebhookEvent.all());
        model.addAttribute("keys", apiKeyRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(key -> new KeyChoice(key.getId(), key.getTitle())).toList());
        model.addAttribute("classes", folderClassRepository.findAll().stream()
                .map(c -> new ClassChoice(c.getId(), c.getTitle())).sorted(Comparator.comparing(ClassChoice::title))
                .collect(Collectors.toList()));
        model.addAttribute("webhooksOn", properties.enabled());
        model.addAttribute("allowedHosts", properties.allowedHosts());
        model.addAttribute("secretsKept", secrets.configured());
    }
}
