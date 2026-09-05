package com.hnp.filemanagement.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * What one person may reach in the folder tree, resolved once and then asked many times
 * (roadmap 6.6).
 *
 * <p>A grant covers everything beneath the folder it names, and {@code folder.path} is built so that
 * "beneath" is a string prefix — {@code /1/5/} is a prefix of {@code /1/5/26/} but not of
 * {@code /1/50/}, because every path carries a trailing slash. So an access decision is a prefix
 * test, and a list query is a set of {@code LIKE 'prefix%'} predicates pushed into SQL rather than a
 * filter applied to rows already fetched — filtering after the fact is what makes paging counts
 * disagree with the page they count.
 *
 * @param unrestricted true for an administrator: the folder check is skipped entirely and no grant
 *                     rows are needed or read
 * @param grantedPaths the granted folder paths, reduced so that no path in the list is covered by a
 *                     shorter one
 */
public record FolderAccess(boolean unrestricted, List<String> grantedPaths) {

    public FolderAccess {
        grantedPaths = List.copyOf(grantedPaths);
    }

    /** Everything is reachable — an administrator, or enforcement switched off. */
    public static FolderAccess everything() {
        return new FolderAccess(true, List.of());
    }

    /** Nothing is reachable — a restricted person with no grants at all. */
    public static FolderAccess nothing() {
        return new FolderAccess(false, List.of());
    }

    /**
     * Builds an access set from raw grants, dropping any path already covered by a shorter one.
     *
     * <p>Reducing matters twice over: it keeps the {@code OR path LIKE ?} list in every filtered
     * query as short as it can be, and it stops the same folder being offered twice as a root of the
     * tree when a user is granted both a parent and its child.
     */
    public static FolderAccess of(List<String> paths) {
        List<String> sorted = paths.stream().distinct().sorted().toList();
        List<String> reduced = new ArrayList<>();
        for (String path : sorted) {
            boolean coveredByShorterGrant = reduced.stream().anyMatch(path::startsWith);
            if (!coveredByShorterGrant) {
                reduced.add(path);
            }
        }
        return new FolderAccess(false, reduced);
    }

    /**
     * Whether the contents of the folder at this path may be read — it <em>is</em> a grant, or sits
     * beneath one.
     */
    public boolean allows(String path) {
        if (unrestricted) {
            return true;
        }
        return grantedPaths.stream().anyMatch(path::startsWith);
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
     */
    public boolean isOnPathTo(String path) {
        if (unrestricted) {
            return true;
        }
        return grantedPaths.stream().anyMatch(granted -> granted.startsWith(path) && !granted.equals(path));
    }

    /** Whether the folder should appear in the tree at all: readable, or a step towards something readable. */
    public boolean visible(String path) {
        return allows(path) || isOnPathTo(path);
    }

    /** True when this person can reach nothing at all — every list is empty and every open is denied. */
    public boolean isEmpty() {
        return !unrestricted && grantedPaths.isEmpty();
    }
}
