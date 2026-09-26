package com.hnp.filemanagement.folder.domain;

import com.hnp.filemanagement.folder.persistence.GrantedPath;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * What one person may reach in the folder tree, resolved once and then asked many times
 * (roadmap 6.6, and 9.1 for the second verb).
 *
 * <p>A grant covers everything beneath the folder it names, and {@code folder.path} is built so that
 * "beneath" is a string prefix — {@code /1/5/} is a prefix of {@code /1/5/26/} but not of
 * {@code /1/50/}, because every path carries a trailing slash. So an access decision is a prefix
 * test, and a list query is a set of {@code LIKE 'prefix%'} predicates pushed into SQL rather than a
 * filter applied to rows already fetched — filtering after the fact is what makes paging counts
 * disagree with the page they count.
 *
 * <p><b>Writable is a subset of readable, and is stored that way.</b> A {@code WRITE} grant appears
 * in both lists rather than only in {@link #writablePaths}, so "may this be read?" never has to
 * remember to check the write list as well. There is no way to express "may write but may not read",
 * which is the point of {@link FolderPermission} being ordered.
 *
 * @param unrestricted  true for an administrator: the folder check is skipped entirely and no grant
 *                      rows are needed or read
 * @param readablePaths every granted path, reduced so that no path in the list is covered by a
 *                      shorter one
 * @param writablePaths the subset of those granted with {@code WRITE}, reduced the same way
 */
public record FolderAccess(boolean unrestricted, List<String> readablePaths, List<String> writablePaths) {

    public FolderAccess {
        readablePaths = List.copyOf(readablePaths);
        writablePaths = List.copyOf(writablePaths);
    }

    /** Everything is reachable — an administrator, or enforcement switched off. */
    public static FolderAccess everything() {
        return new FolderAccess(true, List.of(), List.of());
    }

    /** Nothing is reachable — a restricted person with no grants at all. */
    public static FolderAccess nothing() {
        return new FolderAccess(false, List.of(), List.of());
    }

    /**
     * Builds an access set from raw grants, dropping any path already covered by a shorter one.
     *
     * <p>Reducing matters twice over: it keeps the {@code OR path LIKE ?} list in every filtered
     * query as short as it can be, and it stops the same folder being offered twice as a root of the
     * tree when a user is granted both a parent and its child.
     *
     * <p>The two lists are reduced independently, and they have to be. A {@code READ} on a parent
     * does not cover a {@code WRITE} on its child — the child is the only place writing is allowed —
     * so reducing the write list against the read list would silently widen the grant.
     */
    public static FolderAccess of(Collection<GrantedPath> grants) {
        List<String> readable = reduce(grants.stream().map(GrantedPath::path).toList());
        List<String> writable = reduce(grants.stream()
                .filter(grant -> grant.permission() == FolderPermission.WRITE)
                .map(GrantedPath::path)
                .toList());
        return new FolderAccess(false, readable, writable);
    }

    private static List<String> reduce(List<String> paths) {
        List<String> sorted = paths.stream().distinct().sorted().toList();
        List<String> reduced = new ArrayList<>();
        for (String path : sorted) {
            boolean coveredByShorterGrant = reduced.stream().anyMatch(path::startsWith);
            if (!coveredByShorterGrant) {
                reduced.add(path);
            }
        }
        return reduced;
    }

    /**
     * Whether the contents of the folder at this path may be read — it <em>is</em> a grant, or sits
     * beneath one.
     */
    public boolean canRead(String path) {
        return unrestricted || readablePaths.stream().anyMatch(path::startsWith);
    }

    /**
     * Whether documents may be filed into the folder at this path.
     *
     * <p>Inherited exactly like reading: a {@code WRITE} grant on a category allows writing into
     * every folder beneath it. Nothing else would make sense of "a grant covers everything beneath
     * it", and the alternative — write applying only to the named folder — would need a grant per
     * leaf on a tree whose leaves are where documents actually go.
     */
    public boolean canWrite(String path) {
        return unrestricted || writablePaths.stream().anyMatch(path::startsWith);
    }

    /**
     * Whether this folder is an <em>ancestor</em> of something granted, and so has to be shown even
     * though nothing in it may be read.
     *
     * <p>A grant can sit in the middle of the tree. Someone granted {@code Home/IMS/DocSystem} has no
     * right to {@code Home/IMS} — but if the tree hid {@code IMS} they could never walk down to the
     * folder they do have. So an ancestor is rendered as a signpost: it can be opened, and the only
     * children it reveals are the ones that lead to, or lie inside, a grant. Its own files and its
     * other branches stay hidden, which is what keeps this from being a way around the grant.
     *
     * <p>Only the readable list is consulted, and that is complete: every write grant is in it too.
     */
    public boolean isOnPathTo(String path) {
        if (unrestricted) {
            return true;
        }
        return readablePaths.stream().anyMatch(granted -> granted.startsWith(path) && !granted.equals(path));
    }

    /** Whether the folder should appear in the tree at all: readable, or a step towards something readable. */
    public boolean visible(String path) {
        return canRead(path) || isOnPathTo(path);
    }

    /** True when this person can reach nothing at all — every list is empty and every open is denied. */
    public boolean isEmpty() {
        return !unrestricted && readablePaths.isEmpty();
    }
}
