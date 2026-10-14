package com.hnp.filemanagement.folder.domain;

import com.hnp.filemanagement.folder.persistence.FolderRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The trails of folders - the names from the top down, the root left out - read off their
 * materialised paths: every folder on every path loaded at once, never a query a row and never a
 * walk up {@code getParent()} (the content search's results, 2.15.0; the reports, 12.9).
 */
@Component
public class FolderTrails {

    private final FolderRepository folderRepository;

    public FolderTrails(FolderRepository folderRepository) {
        this.folderRepository = folderRepository;
    }

    /** The names of every folder on these paths, read in one query, for {@link Names#trail}. */
    public Names of(Collection<String> paths) {
        Set<Integer> ids = new HashSet<>();
        for (String path : paths) {
            ids.addAll(idsOf(path));
        }
        Map<Integer, String> names = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Folder folder : folderRepository.findAllById(ids)) {
                if (folder.getKind() != FolderKind.ROOT) {
                    names.put(folder.getId(), folder.getDisplayName());
                }
            }
        }
        return new Names(names);
    }

    /** Folder names by id, as {@link #of} read them. */
    public record Names(Map<Integer, String> byId) {

        /** The trail of a path read with these names: the top-level folder first, the root left out. */
        public List<String> trail(String path) {
            List<String> trail = new ArrayList<>();
            for (Integer id : idsOf(path)) {
                String name = byId.get(id);
                if (name != null) {
                    trail.add(name);
                }
            }
            return trail;
        }
    }

    /** The ids on a path ({@code /1/5/9/}), root first; nothing for null. */
    public static List<Integer> idsOf(String path) {
        List<Integer> ids = new ArrayList<>();
        if (path == null) {
            return ids;
        }
        for (String part : path.split("/")) {
            if (!part.isEmpty()) {
                try {
                    ids.add(Integer.parseInt(part));
                } catch (NumberFormatException ignored) {
                    // a path is ids and slashes; anything else names no folder
                }
            }
        }
        return ids;
    }
}
