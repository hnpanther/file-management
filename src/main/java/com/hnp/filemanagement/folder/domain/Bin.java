package com.hnp.filemanagement.folder.domain;

/**
 * The recycle bin's place in the folder table (roadmap 13.1, {@code V3.20}): one folder of kind
 * {@link FolderKind#TRASH}, <b>outside the tree</b> - no parent, id {@value #ID}, path {@value #PATH} - and whatever
 * was deleted beneath it, each deletion in a folder of its own (its holder, {@code trash_entry.holder_folder_id}).
 *
 * <p><b>Why outside the tree.</b> Every grant names a folder of the tree under the root, and a grant covers what is
 * beneath its folder's path: none is a prefix of {@value #PATH}, so no grant reaches the bin - not one on the root,
 * not a class grant on every folder of a class. What remains are the grants on folders that were deleted - they are
 * kept, for a restore to bring them back - and {@link GrantedFolderPath} and the grant queries leave those out by
 * this path. An administrator, who reads through no grant, is kept out by the path too: {@link FolderAccess} refuses
 * it, {@link ReadScopeSql} and every list written for the unrestricted reader say {@link #NOT_IN}.
 *
 * <p>The id is fixed so that the path is a constant every statement can name without a parameter: no other folder
 * has id 0 (identities begin at 1), and {@code /0/} is a prefix of no other path.
 */
public final class Bin {

    /** The bin folder's id. */
    public static final int ID = 0;

    /** The bin folder's path: a prefix of every path in the bin, and of no other. */
    public static final String PATH = "/0/";

    /** {@code PATH} as the prefix of a {@code LIKE}, for a statement that names it ({@code '/0/%'}). */
    public static final String LIKE = "'/0/%'";

    private Bin() {
    }

    /** Whether the folder at this path is the bin or in it. */
    public static boolean holds(String path) {
        return path != null && path.startsWith(PATH);
    }

    /** Whether this folder is the bin or in it. */
    public static boolean holds(Folder folder) {
        return folder != null && holds(folder.getPath());
    }

    /** A condition, for SQL or JPQL, that the folder {@code alias} is not in the bin: {@code alias.path NOT LIKE '/0/%'}. */
    public static String notIn(String alias) {
        return alias + ".path NOT LIKE " + LIKE;
    }

    /** {@link #notIn} for the alias {@code f} - written out, for an annotation's constant. */
    public static final String NOT_IN = "f.path NOT LIKE '/0/%'";
}
