package com.hnp.filemanagement.folder.domain;

/**
 * Whether the files of a folder are read for the search in contents (2.16.0, {@code V3.16}), as SQL - the
 * one place it is worked out, for the queue, the worker, a move and the pages alike.
 *
 * <p>A folder reads unless the nearest folder above it, itself included, with a setting of its own
 * ({@code folder.content_reading} not null) says it does not: a folder switched off is off with everything
 * below it, and one below it may be switched on again. With no setting anywhere - every folder as the
 * migration left them - everything reads. The folders with a setting of their own are few (a partial index,
 * {@code ix_folder_content_reading}): they are read once into {@link #RULES}, and each folder compared with
 * them on its materialised path, which holds the ids of every folder above it ({@code /1/5/26/}).
 *
 * <p><b>Changes are serialised by one advisory lock</b> ({@link #LOCK}): a change of a setting, and a move,
 * hold it exclusively while they settle the revisions beneath; queueing a revision holds it shared while it
 * decides. So a revision queued while a folder above it is switched on again is decided after the switch
 * has committed - or is there for the switch to queue it - and none is left held back in a folder that reads.
 */
public final class ContentReadingSql {

    /** The advisory lock key of the folders' reading settings. */
    public static final long LOCK = 0x46_4D_43_52_31_36L; // "FMCR16"

    /** The folders with a setting of their own, as a CTE named {@code rules} (without {@code WITH}). */
    public static final String RULES =
            "rules AS MATERIALIZED (SELECT path, content_reading, depth FROM folder WHERE content_reading IS NOT NULL)";

    private ContentReadingSql() {
    }

    /**
     * Whether the folder {@code alias} reads: the nearest setting above it, itself included, or yes. With a
     * space either side, as it is put between the ends of text blocks, which drop their trailing spaces.
     */
    public static String reads(String alias) {
        return " COALESCE((SELECT r.content_reading FROM rules r WHERE " + alias + ".path LIKE r.path || '%' "
                + "ORDER BY r.depth DESC LIMIT 1), TRUE) ";
    }
}
