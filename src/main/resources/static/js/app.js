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
     * The chosen folder is exposed as `chosen` ({id, title, path}) and dispatched as a
     * `folder-chosen` event on the component's root element, for a parent scope to react to.
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

    document.addEventListener("DOMContentLoaded", function () {
        markActiveNavigation();
        enableKeyboardSearch();
        secureNewTabs();
    });
}());
