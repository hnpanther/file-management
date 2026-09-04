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

    document.addEventListener("DOMContentLoaded", function () {
        markActiveNavigation();
        enableKeyboardSearch();
        secureNewTabs();
    });
}());
