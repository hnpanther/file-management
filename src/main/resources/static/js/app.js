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

    /**
     * Copies text to the clipboard; resolves true when it did. The Clipboard API where the page may
     * use it (https, localhost), and the older selection copy otherwise: the application is often
     * served over plain http on the office network, where navigator.clipboard does not exist - which
     * is why the share dialog's copy button did nothing there (2.5.0).
     *
     * The selection copy has to run in the click itself, so it runs first on a page that has no
     * Clipboard API rather than after a failed await; and its hidden field goes next to `near` -
     * the button - so that inside a dialog it is in the part of the page that holds the focus.
     */
    window.appCopy = async function (text, near) {
        if (navigator.clipboard && window.isSecureContext) {
            try {
                await navigator.clipboard.writeText(text);
                return true;
            } catch (e) {
                // Refused (no permission, no focus): fall through to the selection copy.
            }
        }
        var host = (near && near.parentNode) || document.body;
        var area = document.createElement("textarea");
        area.value = text;
        area.setAttribute("readonly", "");
        area.setAttribute("aria-hidden", "true");
        area.style.position = "fixed";
        area.style.top = "0";
        area.style.left = "0";
        area.style.width = "1px";
        area.style.height = "1px";
        area.style.opacity = "0";
        host.appendChild(area);
        area.focus();
        area.select();
        area.setSelectionRange(0, text.length);
        var copied = false;
        try {
            copied = document.execCommand("copy");
        } catch (e) {
            copied = false;
        }
        host.removeChild(area);
        if (near && typeof near.focus === "function") {
            near.focus();
        }
        return copied;
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
     * config: { url, initialId, initialPath: [{id,title}], rootTitle, selectRoot, copy: {loadFailed, more} }
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
            childPage: null,   // which page of the folder's child folders (roadmap 12.4)
            filter: "",        // narrows them by name: a level may hold thousands
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
                if (!this.current || folderId !== this.current.id) {
                    this.filter = "";
                }
                return this.load(folderId, 0, false);
            },

            /** The next page of the folder's children, after the ones on screen. */
            more: function () {
                if (!this.current || !this.childPage || this.loading) {
                    return;
                }
                return this.load(this.current.id, this.childPage.number + 1, true);
            },

            hasMore: function () {
                return this.childPage !== null && this.childPage.number < this.childPage.totalPages - 1;
            },

            moreLabel: function () {
                var left = this.childPage ? this.childPage.totalElements - this.children.length : 0;
                return (config.copy.more || "{0}").replace("{0}", left.toLocaleString("fa-IR"));
            },

            applyFilter: function () {
                if (this.current) {
                    return this.load(this.current.id, 0, false);
                }
            },

            load: async function (folderId, folderPage, append) {
                this.loading = true;
                this.error = "";
                try {
                    var url = config.url + "?size=1&folderPage=" + folderPage
                        + (folderId ? "&folderId=" + encodeURIComponent(folderId) : "")
                        + (this.filter.trim() ? "&filter=" + encodeURIComponent(this.filter.trim()) : "");
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
                    this.childPage = data.folderPage || null;
                    if (append) {
                        // Another page of the same folder: the choice does not change.
                        this.children = this.children.concat(data.folders);
                        return;
                    }
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

            copy: async function (button) {
                if (!this.result) {
                    return;
                }
                // appCopy, not navigator.clipboard alone: over plain http - how the office network
                // reaches the server - there is no Clipboard API, and the button did nothing.
                this.copied = await window.appCopy(this.result.url, button);
                if (this.copied) {
                    var panel = this;
                    setTimeout(function () { panel.copied = false; }, 2000);
                }
            }
        };
    };

    /**
     * The folder-access tree of the role page and the API key page (roadmap 12.4): the rows the
     * server rendered - the top, every granted folder and the way to it - and the rest added on
     * demand, a level at a time ("more" for the next page of a wide level) or by name.
     *
     * Two rules hold everything else up. A row is never removed: closing a folder hides the rows
     * beneath it, and a hidden <select> is still posted - the form posts the complete selection, so
     * a removed row would read as a grant taken away. And what a row inherits is worked out here,
     * from the selects on screen, whenever one changes - so "reached through the folder above" is
     * true of what is about to be saved, not of what was saved before.
     */
    function initFolderGrantTree(tree) {
        var rows = tree.querySelector("[data-grant-rows]");
        var template = tree.querySelector("[data-grant-row-template]");
        var search = tree.querySelector("[data-grant-search]");
        var hits = tree.querySelector("[data-grant-hits]");
        var disabled = tree.dataset.disabled === "true";
        var copy = {
            more: tree.dataset.copyMore,
            inherited: tree.dataset.copyInherited,
            inheritedWrite: tree.dataset.copyInheritedWrite,
            loadFailed: tree.dataset.copyLoadFailed,
            searchEmpty: tree.dataset.copySearchEmpty,
            expand: tree.dataset.copyExpand,
            collapse: tree.dataset.copyCollapse
        };

        function rowOf(id) {
            return rows.querySelector('.folder-grant-option[data-id="' + id + '"]');
        }

        function idsIn(path) {
            return path.split("/").filter(function (segment) { return segment !== ""; });
        }

        /** The rows directly and indirectly under this one: everything after it that is deeper. */
        function subtreeOf(row) {
            var depth = Number(row.dataset.depth);
            var found = [];
            var next = row.nextElementSibling;
            // A "more" row carries the depth of the children it stands for, so it belongs here too.
            while (next && Number(next.dataset.depth) > depth) {
                found.push(next);
                next = next.nextElementSibling;
            }
            return found;
        }

        function childrenShown(row) {
            var depth = Number(row.dataset.depth);
            return subtreeOf(row).filter(function (r) {
                return r.dataset.more === undefined && Number(r.dataset.depth) === depth + 1;
            }).length;
        }

        function setOpen(row, open) {
            row.dataset.open = open ? "true" : "false";
            var toggle = row.querySelector("[data-grant-toggle]");
            if (toggle) {
                toggle.setAttribute("aria-label", open ? copy.collapse : copy.expand);
                toggle.setAttribute("aria-expanded", open ? "true" : "false");
                toggle.querySelector("i").className = "bi text-[0.65rem] " + (open ? "bi-chevron-down" : "bi-chevron-left");
            }
        }

        /** Hidden while any folder above it is closed. */
        function refreshVisibility() {
            var closedPaths = [];
            Array.prototype.forEach.call(rows.children, function (row) {
                var path = row.dataset.path || row.dataset.parentPath + "~";
                closedPaths = closedPaths.filter(function (closed) { return path.indexOf(closed) === 0; });
                var hidden = closedPaths.length > 0;
                row.hidden = hidden;
                if (row.dataset.more === undefined && row.dataset.open === "false") {
                    closedPaths.push(row.dataset.path);
                }
            });
        }

        /** What each row is reached through, from the selects on screen: the strongest above it. */
        function refreshInherited() {
            var chosen = {};
            Array.prototype.forEach.call(rows.querySelectorAll(".folder-grant-option[data-id]"), function (row) {
                var value = row.querySelector("select").value;
                chosen[row.dataset.id] = value ? value.split(":")[1] : "";
            });
            Array.prototype.forEach.call(rows.querySelectorAll(".folder-grant-option[data-id]"), function (row) {
                var above = idsIn(row.dataset.path).slice(0, -1);
                var strongest = "";
                above.forEach(function (id) {
                    if (chosen[id] === "WRITE" || (chosen[id] === "READ" && strongest === "")) {
                        strongest = chosen[id];
                    }
                });
                var badge = row.querySelector("[data-grant-badge]");
                badge.hidden = strongest === "";
                badge.textContent = strongest === "WRITE" ? copy.inheritedWrite : copy.inherited;
                row.classList.toggle("is-covered", strongest !== "");
            });
        }

        function iconFor(depth) {
            return depth === 0 ? "bi-house-door" : depth === 1 ? "bi-folder-fill" : depth === 2 ? "bi-folder" : "bi-folder2";
        }

        /** A row for a folder the server sent (FolderGrantDTO), not yet granted anything. */
        function newRow(folder) {
            var row = template.content.firstElementChild.cloneNode(true);
            row.dataset.id = folder.id;
            row.dataset.path = folder.path;
            row.dataset.depth = folder.depth;
            row.dataset.children = folder.childCount;
            row.dataset.name = folder.name;
            row.dataset.open = "false";
            row.style.setProperty("--folder-depth", folder.depth);
            row.querySelector("[data-grant-icon]").classList.add(iconFor(folder.depth));
            row.querySelector("[data-grant-name]").textContent = folder.displayName || folder.name;
            row.querySelector("[data-grant-technical]").textContent = folder.name;
            var select = row.querySelector("select");
            select.id = "folder-grant-" + folder.id;
            select.disabled = disabled;
            row.querySelector("label").htmlFor = select.id;
            row.querySelector("[data-grant-read]").value = folder.id + ":READ";
            row.querySelector("[data-grant-write]").value = folder.id + ":WRITE";
            var toggle = row.querySelector("[data-grant-toggle]");
            if (Number(folder.childCount) === 0) {
                toggle.disabled = true;
                toggle.classList.add("is-leaf");
            }
            setOpen(row, false);
            return row;
        }

        /** "N more folders": the rest of a level, from page {@code nextPage}, in place of itself. */
        function moreRow(parent, nextPage, left) {
            var row = document.createElement("button");
            row.type = "button";
            row.className = "folder-grant-more";
            row.dataset.more = "";
            row.dataset.parentId = parent.dataset.id;
            row.dataset.parentPath = parent.dataset.path;
            row.dataset.depth = Number(parent.dataset.depth) + 1;
            row.dataset.nextPage = nextPage;
            row.style.setProperty("--folder-depth", Number(parent.dataset.depth) + 1);
            row.textContent = copy.more.replace("{0}", Math.max(0, left).toLocaleString("fa-IR"));
            row.addEventListener("click", function () { loadLevel(parent, Number(row.dataset.nextPage), row); });
            return row;
        }

        /** The end of a row's subtree, where its next children go. */
        function insertionPointUnder(parent) {
            var subtree = subtreeOf(parent);
            var last = subtree.length ? subtree[subtree.length - 1] : parent;
            return last.nextElementSibling;
        }

        async function loadLevel(parent, page, replacing) {
            parent.dataset.loading = "true";
            try {
                var response = await fetch(tree.dataset.childrenUrl + "?parentId=" + encodeURIComponent(parent.dataset.id)
                    + "&page=" + page, {
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
                var level = await response.json();
                var before = replacing ? replacing : insertionPointUnder(parent);
                level.rows.forEach(function (folder) {
                    if (!rowOf(folder.id)) {
                        rows.insertBefore(newRow(folder), before);
                    }
                });
                if (level.page.number < level.page.totalPages - 1) {
                    var page = level.page;
                    rows.insertBefore(moreRow(parent, page.number + 1,
                        page.totalElements - (page.number + 1) * page.size), before);
                }
                if (replacing) {
                    replacing.remove();
                }
                parent.dataset.loaded = "true";
                refreshInherited();
                refreshVisibility();
            } catch (e) {
                window.alert(copy.loadFailed);
            } finally {
                delete parent.dataset.loading;
            }
        }

        async function toggle(row) {
            if (row.dataset.loading) {
                return;
            }
            if (row.dataset.open === "true") {
                setOpen(row, false);
                refreshVisibility();
                return;
            }
            setOpen(row, true);
            refreshVisibility();
            if (!row.dataset.loaded && childrenShown(row) < Number(row.dataset.children)) {
                await loadLevel(row, 0, null);
            }
        }

        /** Makes sure a folder found by name has a row, with the rows above it, and shows it. */
        function reveal(hit) {
            var parent = null;
            hit.chain.concat([hit.folder]).forEach(function (folder) {
                var row = rowOf(folder.id);
                if (!row) {
                    row = newRow(folder);
                    var anchor = parent ? parent : rows.querySelector('.folder-grant-option[data-depth="0"]');
                    rows.insertBefore(row, anchor ? insertionPointUnder(anchor) : null);
                }
                if (parent) {
                    setOpen(parent, true);
                }
                parent = row;
            });
            refreshInherited();
            refreshVisibility();
            if (parent) {
                parent.scrollIntoView({ block: "center" });
                parent.classList.add("is-found");
                setTimeout(function () { parent.classList.remove("is-found"); }, 2500);
                parent.querySelector("select").focus();
            }
        }

        var searching = null;
        async function runSearch() {
            var term = search.value.trim();
            hits.innerHTML = "";
            hits.hidden = term === "";
            if (term === "") {
                return;
            }
            var asked = term;
            searching = asked;
            try {
                var response = await fetch(tree.dataset.searchUrl + "?query=" + encodeURIComponent(term), {
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
                var found = await response.json();
                if (searching !== asked) {
                    return;
                }
                hits.innerHTML = "";
                if (found.length === 0) {
                    var none = document.createElement("li");
                    none.className = "folder-grant-hit is-empty";
                    none.textContent = copy.searchEmpty;
                    hits.appendChild(none);
                }
                found.forEach(function (hit) {
                    var item = document.createElement("li");
                    var button = document.createElement("button");
                    button.type = "button";
                    button.className = "folder-grant-hit";
                    var name = document.createElement("span");
                    name.className = "block truncate";
                    name.textContent = hit.folder.displayName || hit.folder.name;
                    var where = document.createElement("span");
                    where.className = "block truncate text-xs text-ink-400";
                    where.textContent = hit.chain.map(function (c) { return c.displayName || c.name; }).join(" ◂ ");
                    button.appendChild(name);
                    button.appendChild(where);
                    button.addEventListener("click", function () {
                        hits.hidden = true;
                        reveal(hit);
                    });
                    item.appendChild(button);
                    hits.appendChild(item);
                });
            } catch (e) {
                hits.innerHTML = "";
                var failed = document.createElement("li");
                failed.className = "folder-grant-hit is-empty";
                failed.textContent = copy.loadFailed;
                hits.appendChild(failed);
            }
        }

        // Rows the server rendered: open where their children are on screen, closed where not - and
        // where only some are (the way to a grant), "N more folders" for the rest, from the start.
        Array.prototype.forEach.call(rows.querySelectorAll(".folder-grant-option[data-id]"), function (row) {
            var shown = childrenShown(row);
            setOpen(row, shown > 0);
            if (shown > 0 && shown < Number(row.dataset.children)) {
                rows.insertBefore(moreRow(row, 0, Number(row.dataset.children) - shown), insertionPointUnder(row));
            }
        });
        rows.addEventListener("click", function (event) {
            var button = event.target.closest("[data-grant-toggle]");
            if (button && !button.disabled) {
                event.preventDefault();
                toggle(button.closest(".folder-grant-option"));
            }
        });
        rows.addEventListener("change", refreshInherited);
        var debounce = null;
        search.addEventListener("input", function () {
            clearTimeout(debounce);
            debounce = setTimeout(runSearch, 350);
        });
        search.addEventListener("keydown", function (event) {
            if (event.key === "Enter") {
                event.preventDefault();
                clearTimeout(debounce);
                runSearch();
            }
        });
        refreshInherited();
        refreshVisibility();
    }

    /**
     * The metadata editor (roadmap 12.2, 12.3): a document as rows of a key and a text value, or as
     * JSON for what rows cannot say - a nested value, a number, a list. Whichever is on screen is
     * what the hidden `metadata` field posts: rows as a JSON object, nothing for no row. The server
     * checks it (MetadataRules) and says which rule it breaks; what is checked here only saves a
     * round trip.
     *
     * config: { initial: "<the document as JSON, or empty>", copy: { invalid, notFlat, duplicate } }
     */
    window.metadataEditor = function (config) {
        function isFlat(doc) {
            return doc !== null && typeof doc === "object" && !Array.isArray(doc)
                && Object.keys(doc).every(function (key) { return typeof doc[key] === "string"; });
        }
        return {
            mode: "rows",
            rows: [],
            text: "",
            error: "",

            init: function () {
                var initial = (config.initial || "").trim();
                this.text = initial;
                if (!initial) {
                    this.rows = [{ key: "", value: "" }];
                    return;
                }
                try {
                    var doc = JSON.parse(initial);
                    if (isFlat(doc)) {
                        this.rows = Object.keys(doc).map(function (key) { return { key: key, value: doc[key] }; });
                    } else {
                        this.mode = "json";
                    }
                } catch (e) {
                    this.mode = "json";
                }
                if (!this.rows.length) {
                    this.rows = [{ key: "", value: "" }];
                }
            },
            addRow: function () {
                this.rows.push({ key: "", value: "" });
            },
            removeRow: function (index) {
                this.rows.splice(index, 1);
                if (!this.rows.length) {
                    this.addRow();
                }
            },
            rowsDocument: function () {
                var doc = {};
                var duplicate = false;
                this.rows.forEach(function (row) {
                    var key = (row.key || "").trim();
                    if (!key) {
                        return;
                    }
                    if (Object.prototype.hasOwnProperty.call(doc, key)) {
                        duplicate = true;
                    }
                    doc[key] = row.value || "";
                });
                return { doc: doc, duplicate: duplicate };
            },
            toJson: function () {
                var built = this.rowsDocument();
                this.text = Object.keys(built.doc).length ? JSON.stringify(built.doc, null, 2) : "";
                this.error = "";
                this.mode = "json";
            },
            toRows: function () {
                var doc;
                try {
                    doc = this.text.trim() ? JSON.parse(this.text) : {};
                } catch (e) {
                    this.error = config.copy.invalid;
                    return;
                }
                if (!isFlat(doc)) {
                    this.error = config.copy.notFlat;
                    return;
                }
                this.rows = Object.keys(doc).map(function (key) { return { key: key, value: doc[key] }; });
                if (!this.rows.length) {
                    this.addRow();
                }
                this.error = "";
                this.mode = "rows";
            },
            /** What the hidden field posts. */
            get value() {
                if (this.mode === "json") {
                    return this.text.trim();
                }
                var built = this.rowsDocument();
                return Object.keys(built.doc).length ? JSON.stringify(built.doc) : "";
            },
            /** Checked as the form is sent: a refusal here keeps what was typed on screen. */
            check: function (event) {
                this.error = "";
                if (this.mode === "rows" && this.rowsDocument().duplicate) {
                    this.error = config.copy.duplicate;
                } else if (this.mode === "json" && this.text.trim()) {
                    try {
                        var doc = JSON.parse(this.text);
                        if (doc === null || typeof doc !== "object" || Array.isArray(doc)) {
                            this.error = config.copy.invalid;
                        }
                    } catch (e) {
                        this.error = config.copy.invalid;
                    }
                }
                if (this.error) {
                    event.preventDefault();
                }
            }
        };
    };

    window.initFolderGrantTrees = function () {
        document.querySelectorAll("[data-folder-grant-tree]").forEach(initFolderGrantTree);
    };

    document.addEventListener("DOMContentLoaded", function () {
        markActiveNavigation();
        enableKeyboardSearch();
        secureNewTabs();
        window.initFolderGrantTrees();
    });
}());
