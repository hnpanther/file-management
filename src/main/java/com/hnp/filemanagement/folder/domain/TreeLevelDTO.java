package com.hnp.filemanagement.folder.domain;

import java.util.List;

/**
 * One page of a level of the tree page (roadmap 12.4): a folder's child folders, then its files,
 * as one list paged together - so a level of thousands opens a page at a time, with "more" for the
 * rest, rather than as one array of every node.
 *
 * @param nodes  this page's nodes, folders before files, each in name order
 * @param page   which page of the level this is, and how many nodes the level holds in all
 * @param filter the filter the level was narrowed by, trimmed; {@code ""} for none
 */
public record TreeLevelDTO(List<TreeNodeDTO> nodes, FolderContentDTO.PageInfo page, String filter) {

    /** A level that is never paged - a file's versions - as one page holding all of it. */
    public static TreeLevelDTO whole(List<TreeNodeDTO> nodes) {
        return new TreeLevelDTO(nodes, new FolderContentDTO.PageInfo(0, Math.max(nodes.size(), 1), 1, nodes.size()), "");
    }
}
