package com.hnp.filemanagement.folder.domain;

import com.hnp.filemanagement.folder.persistence.GrantedPath;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

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
 * <p><b>Thousands of paths, each check a lookup</b> (roadmap Phase 14). A class grant covers every folder of
 * its class - one per contract - so a reader may hold thousands of paths. Reduced, no path is beneath
 * another, and kept sorted: the one that could cover a path is the greatest at or before it, and those
 * beneath a path follow it - so {@link #canRead}, {@link #isOnPathTo} and {@link #visibleChildIdsUnder}
 * are a lookup in a sorted set, not a scan of every grant.
 */
public final class FolderAccess {

    private final boolean unrestricted;
    private final NavigableSet<String> readable;
    private final NavigableSet<String> writable;
    private final boolean classGrants;

    public FolderAccess(boolean unrestricted, List<String> readablePaths, List<String> writablePaths) {
        this(unrestricted, readablePaths, writablePaths, false);
    }

    private FolderAccess(boolean unrestricted, Collection<String> readablePaths, Collection<String> writablePaths,
                         boolean classGrants) {
        this.unrestricted = unrestricted;
        this.readable = Collections.unmodifiableNavigableSet(reduce(readablePaths));
        this.writable = Collections.unmodifiableNavigableSet(reduce(writablePaths));
        this.classGrants = classGrants;
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
        return of(grants, false);
    }

    /** {@link #of(Collection)}, saying whether a role of the person holds a class grant (roadmap Phase 14). */
    public static FolderAccess of(Collection<GrantedPath> grants, boolean classGrants) {
        List<String> readable = grants.stream().map(GrantedPath::path).toList();
        List<String> writable = grants.stream()
                .filter(grant -> grant.permission() == FolderPermission.WRITE)
                .map(GrantedPath::path)
                .toList();
        return new FolderAccess(false, readable, writable, classGrants);
    }

    /** Whether a role of the person holds a class grant - whatever folders it covers today (roadmap Phase 14). */
    public boolean hasClassGrants() {
        return classGrants;
    }

    /**
     * Sorted, then each path kept unless the last one kept is a prefix of it: in sorted order whatever a
     * path covers follows it at once, so the last one kept is the only one that could - n log n, not n².
     */
    private static NavigableSet<String> reduce(Collection<String> paths) {
        NavigableSet<String> reduced = new TreeSet<>();
        String lastKept = null;
        for (String path : new TreeSet<>(paths)) {
            if (lastKept == null || !path.startsWith(lastKept)) {
                reduced.add(path);
                lastKept = path;
            }
        }
        return reduced;
    }

    /**
     * True for an administrator, or with enforcement off: no grant is read, and everything in the tree may be reached
     * - never the recycle bin ({@link Bin}), which {@link #canRead}, {@link #canWrite} and {@link #visible} refuse to
     * every reader. A caller that answers yes on this alone, without asking about the path, has to leave the bin out
     * itself.
     */
    public boolean unrestricted() {
        return unrestricted;
    }

    /** Every granted path, reduced so that no path in the list is covered by a shorter one, sorted. */
    public List<String> readablePaths() {
        return List.copyOf(readable);
    }

    /** The paths granted with {@code WRITE}, reduced the same way, sorted. */
    public List<String> writablePaths() {
        return List.copyOf(writable);
    }

    /**
     * Whether the contents of the folder at this path may be read — it <em>is</em> a grant, or sits
     * beneath one.
     */
    public boolean canRead(String path) {
        return !Bin.holds(path) && (unrestricted || covered(readable, path));
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
        return !Bin.holds(path) && (unrestricted || covered(writable, path));
    }

    /**
     * Whether a path of this reduced set is a prefix of {@code path}. Only the greatest one at or before it
     * can be: every string between a prefix of {@code path} and {@code path} itself starts with that prefix,
     * and no path of the set starts with another.
     */
    private static boolean covered(NavigableSet<String> paths, String path) {
        String floor = paths.floor(path);
        return floor != null && path.startsWith(floor);
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
     * <p>Only the readable list is consulted, and that is complete: every write grant is in it too. The
     * paths beneath this one follow it in sorted order, so the first after it says.
     */
    public boolean isOnPathTo(String path) {
        if (Bin.holds(path)) {
            return false;
        }
        if (unrestricted) {
            return true;
        }
        String next = readable.higher(path);
        return next != null && next.startsWith(path);
    }

    /**
     * Which children of the folder at this path appear, as a filter a query of the level can take
     * (roadmap 12.4): every one when the folder itself is readable - empty {@link Optional} - and
     * otherwise only those on the way to a grant, which are named by the grants themselves: the
     * segment of each granted path right after this one. So a level of thousands, walked through
     * on the way to one grant, is a query for that one child, not a read of the thousands to keep
     * one.
     *
     * <p>Exactly {@link #visible} applied to each child: a child of a folder that is not readable
     * is readable only if it is itself a grant, and then it is on that set too.
     */
    public Optional<Set<Integer>> visibleChildIdsUnder(String parentPath) {
        if (Bin.holds(parentPath)) {
            return Optional.of(Set.of());
        }
        if (canRead(parentPath)) {
            return Optional.empty();
        }
        Set<Integer> ids = new LinkedHashSet<>();
        for (String granted : readable.tailSet(parentPath, false)) {
            if (!granted.startsWith(parentPath)) {
                break;
            }
            String rest = granted.substring(parentPath.length());
            ids.add(Integer.valueOf(rest.substring(0, rest.indexOf('/'))));
        }
        return Optional.of(ids);
    }

    /** Whether the folder should appear in the tree at all: readable, or a step towards something readable. */
    public boolean visible(String path) {
        return canRead(path) || isOnPathTo(path);
    }

    /** True when this person can reach nothing at all — every list is empty and every open is denied. */
    public boolean isEmpty() {
        return !unrestricted && readable.isEmpty();
    }
}
