/*
 * Shared behaviour for every page.
 *
 * Interaction state (drawer, dropdowns, dialogs) is declared in the markup with Alpine. This file
 * is only for things that are genuinely global and not tied to one element.
 */
(function () {
    "use strict";

    function normalizePath(path) {
        if (!path || path === "/") {
            return "/";
        }
        return path.replace(/\/+$/, "");
    }

    /** Marks the sidebar link that best matches the current URL. */
    function markActiveNavigation() {
        var current = normalizePath(window.location.pathname);
        var links = document.querySelectorAll("#app-sidebar a[href]");
        var best = null;

        links.forEach(function (link) {
            var path;
            try {
                path = normalizePath(new URL(link.href, window.location.origin).pathname);
            } catch (ignored) {
                return;
            }
            if (path === "/" || !(current === path || current.indexOf(path + "/") === 0)) {
                return;
            }
            if (!best || path.length > best.path.length) {
                best = { link: link, path: path };
            }
        });

        if (best) {
            best.link.classList.add("active");
            best.link.setAttribute("aria-current", "page");
        }
    }

    /** Enter in a search box runs the page's own search() rather than submitting nothing. */
    function enableKeyboardSearch() {
        document.querySelectorAll("#search").forEach(function (input) {
            input.setAttribute("autocomplete", "off");
            input.addEventListener("keydown", function (event) {
                if (event.key === "Enter" && typeof window.search === "function") {
                    event.preventDefault();
                    window.search();
                }
            });
        });
    }

    function secureNewTabs() {
        document.querySelectorAll("a[target='_blank']").forEach(function (link) {
            link.setAttribute("rel", "noopener noreferrer");
        });
    }

    /**
     * CSRF token for hand-written fetch/AJAX calls, read from the meta tags the shared head
     * fragment renders. Exposed so page scripts do not each re-implement the lookup.
     */
    /**
     * The installation's time zone (filemanagement.time-zone), from the page's head. Every time
     * the server sends is an instant; it is shown on this wall clock, never the browser's or the
     * server's (issue 24). Undefined - the browser's own zone - only on a page without the head.
     */
    window.appTimeZone = function () {
        var meta = document.querySelector("meta[name='app-time-zone']");
        return (meta && meta.getAttribute("content")) || undefined;
    };

    /** "2026-09-22 11:30" for an ISO instant, on the installation's clock; the value as it came if it cannot be read. */
    window.appDateTime = function (value) {
        if (!value) {
            return "";
        }
        try {
            var parts = {};
            new Intl.DateTimeFormat("en-CA", {
                timeZone: window.appTimeZone(), calendar: "gregory", numberingSystem: "latn",
                year: "numeric", month: "2-digit", day: "2-digit",
                hour: "2-digit", minute: "2-digit", hourCycle: "h23"
            }).formatToParts(new Date(value)).forEach(function (part) {
                parts[part.type] = part.value;
            });
            return parts.year + "-" + parts.month + "-" + parts.day + " " + parts.hour + ":" + parts.minute;
        } catch (e) {
            return String(value);
        }
    };

    window.appCsrf = function () {
        var token = document.querySelector("meta[name='_csrf']");
        var header = document.querySelector("meta[name='_csrf_header']");
        return {
            token: token ? token.getAttribute("content") : "",
            header: header ? header.getAttribute("content") : ""
        };
    };

    /**
     * The session has ended (docs/issues.md, issue 77). A resource endpoint answers a script's
     * call with 401 in that case, never with a redirect to the login page, so this is the one
     * place that decides what a page does about it: go and sign in again. The explorer overrides
     * this with an in-page message, because it has state worth keeping on screen.
     */
    window.appSessionExpired = function () {
        window.location.assign("/login");
    };

    /** Every jQuery page gets the handling for free; fetch callers call appSessionExpired themselves. */
    if (window.jQuery) {
        window.jQuery(document).ajaxError(function (event, xhr) {
            if (xhr && xhr.status === 401) {
                window.appSessionExpired();
            }
        });
    }

    /**
     * A folder chooser: one Alpine component, used wherever a page asks "which folder?" - the
     * upload form's target and the explorer's move dialog. It drills down through
     * /resource/folders/children, the same endpoint the explorer reads, so it shows exactly the
     * folders the person may walk into, and answers with the folder they pick.
     *
     * config: { url, initialId, initialPath: [{id,title}], rootTitle, selectRoot, copy: {loadFailed} }
     * The folder on screen is the choice, updated on every step of the drill-down: it is exposed
     * as `chosen` ({id, title, path}, or null while the root is on screen and the caller does not
     * take it) and dispatched as a `folder-chosen` event on the component's root element, for a
     * parent scope to react to.
     */
    window.folderChooser = function (config) {
        return {
            crumbs: [],        // the way down to `current`, root first
            current: null,     // the folder on screen: {id, title, kind}
            children: [],
            loading: false,
            error: "",
            chosen: null,

            init: function () {
                var initialPath = config.initialPath || [];
                if (config.initialId) {
                    this.chosen = {
                        id: config.initialId,
                        title: initialPath.length ? initialPath[initialPath.length - 1].title : "",
                        path: initialPath.map(function (c) { return c.title; }).join(" / ")
                    };
                }
                this.open(config.initialId || null);
            },

            open: async function (folderId) {
                this.loading = true;
                this.error = "";
                try {
                    var url = config.url + "?size=1" + (folderId ? "&folderId=" + encodeURIComponent(folderId) : "");
                    var response = await fetch(url, {
                        headers: { "Accept": "application/json", "X-Requested-With": "XMLHttpRequest" },
                        credentials: "same-origin"
                    });
                    if (response.status === 401) {
                        window.appSessionExpired();
                        return;
                    }
                    if (!response.ok) {
                        throw new Error("HTTP " + response.status);
                    }
                    var data = await response.json();
                    this.current = data.folder;
                    this.crumbs = data.breadcrumb.concat([data.folder]);
                    this.children = data.folders;
                    // The folder on screen is the choice: drilling down is choosing, and the
                    // button below only says so out loud. Nothing is chosen while the root is
                    // on screen and the caller does not take it.
                    if (this.selectable()) {
                        this.choose();
                    } else {
                        this.chosen = null;
                        this.$dispatch("folder-chosen", null);
                    }
                } catch (e) {
                    this.error = config.copy.loadFailed;
                } finally {
                    this.loading = false;
                }
            },

            crumbTitle: function (crumb) {
                return crumb.kind === "ROOT" ? config.rootTitle : crumb.title;
            },

            /** Whether the folder on screen may be picked: any folder, or the root too when the caller allows it. */
            selectable: function () {
                return this.current !== null && (config.selectRoot || this.current.kind !== "ROOT");
            },

            choose: function () {
                if (!this.selectable()) {
                    return;
                }
                var titles = this.crumbs.filter(function (c) { return c.kind !== "ROOT"; })
                    .map(function (c) { return c.title; });
                this.chosen = {
                    id: this.current.id,
                    title: this.crumbTitle(this.current),
                    path: titles.length ? titles.join(" / ") : config.rootTitle
                };
                this.$dispatch("folder-chosen", this.chosen);
            }
        };
    };

    /**
     * A share-link panel (roadmap 10.5): one Alpine component, opened from the explorer's file
     * pane and from the file page's revision rows. It asks for the validity in minutes (the cap
     * shown, a larger number clamped by the server), an optional or required password, and an
     * optional number of downloads; posts once; and shows the URL - the one time the token is
     * ever shown - with a copy button.
     *
     * config: { createUrl: "/resource/files/file-details/{id}/share-links", maxMinutes,
     *           defaultMinutes, passwordRequired, copy: {failed, copied} }
     */
    window.shareLinkPanel = function (config) {
        return {
            open: false,
            subject: null,      // {fileDetailsId, label}
            minutes: config.defaultMinutes,
            password: "",
            maxDownloads: "",
            busy: false,
            error: "",
            result: null,       // {url, expiresAt, maxDownloads, passwordProtected}
            copied: false,

            maxMinutes: config.maxMinutes,
            passwordRequired: !!config.passwordRequired,

            start: function (fileDetailsId, label) {
                this.subject = { fileDetailsId: fileDetailsId, label: label };
                this.minutes = config.defaultMinutes;
                this.password = "";
                this.maxDownloads = "";
                this.error = "";
                this.result = null;
                this.copied = false;
                this.open = true;
            },

            close: function () {
                this.open = false;
                this.result = null;
            },

            expiry: function () {
                return this.result ? window.appDateTime(this.result.expiresAt) : "";
            },

            submit: async function () {
                if (!this.subject || this.busy) {
                    return;
                }
                this.busy = true;
                this.error = "";
                var csrf = window.appCsrf();
                var headers = { "Accept": "application/json", "Content-Type": "application/json", "X-Requested-With": "XMLHttpRequest" };
                if (csrf.header) {
                    headers[csrf.header] = csrf.token;
                }
                var body = {
                    minutes: this.minutes === "" ? null : Number(this.minutes),
                    password: this.password === "" ? null : this.password,
                    maxDownloads: this.maxDownloads === "" ? null : Number(this.maxDownloads)
                };
                try {
                    var response = await fetch(config.createUrl.replace("{id}", encodeURIComponent(this.subject.fileDetailsId)),
                        { method: "POST", headers: headers, body: JSON.stringify(body) });
                    if (response.status === 401) {
                        window.appSessionExpired();
                        return;
                    }
                    if (!response.ok) {
                        var problem = null;
                        try { problem = await response.json(); } catch (ignored) { }
                        this.error = (problem && problem.detail) || config.copy.failed;
                        return;
                    }
                    var created = await response.json();
                    this.result = {
                        url: created.url,
                        expiresAt: created.link.expiresAt,
                        maxDownloads: created.link.maxDownloads,
                        passwordProtected: created.link.passwordProtected
                    };
                    this.password = "";
                } catch (e) {
                    this.error = config.copy.failed;
                } finally {
                    this.busy = false;
                }
            },

            copy: async function () {
                if (!this.result) {
                    return;
                }
                try {
                    await navigator.clipboard.writeText(this.result.url);
                    this.copied = true;
                } catch (e) {
                    // No clipboard (an http origin, an old browser): the field is selectable.
                    this.copied = false;
                }
            }
        };
    };

    document.addEventListener("DOMContentLoaded", function () {
        markActiveNavigation();
        enableKeyboardSearch();
        secureNewTabs();
    });
}());
