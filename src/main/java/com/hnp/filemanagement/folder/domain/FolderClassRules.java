package com.hnp.filemanagement.folder.domain;

import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.shared.util.SearchKey;

import java.util.List;
import java.util.Optional;

/**
 * The class a folder takes when it is made (roadmap Phase 14) - from its parent's class, never from its name
 * alone: a name the parent class's template holds takes that template folder's class; any other name follows
 * the parent class's rule for other children - none, a given class, or refused. A folder whose parent has no
 * class has none. Names are compared as a folder's siblings' are, folded ({@link SearchKey}), so the template's
 * {@code Documents} is n8n's {@code documents}.
 *
 * <p>Asked wherever a folder is made, renamed or moved in ({@code FolderService}), and before a multipart upload
 * begins ({@code S3ObjectService.preflightPut}) - one rule for the web, v1 and S3.
 */
public final class FolderClassRules {

    private FolderClassRules() {
    }

    /**
     * The class a new folder named {@code name} takes under a folder of {@code parentClass} (null: a parent
     * without a class), or null for none.
     *
     * @throws InvalidDataException {@code folderClass.refused} when the parent's class refuses a name its
     *                              template does not hold
     */
    public static FolderClass classFor(FolderClass parentClass, String name) {
        if (parentClass == null) {
            return null;
        }
        Optional<FolderClassChild> template = templateNamed(parentClass, name);
        if (template.isPresent()) {
            return template.get().getChildClass();
        }
        return switch (parentClass.getOtherChildren()) {
            case NONE -> null;
            case CLASS -> parentClass.getOtherChildrenClass();
            case REFUSE -> throw refused(parentClass, name);
        };
    }

    /**
     * Refuses a folder named {@code name} under a folder of {@code parentClass} - a rename to it, or a move
     * there - when the class refuses a name its template does not hold. A folder that keeps its class: only
     * where it may stand is asked, not what it is.
     */
    public static void requireAllowedUnder(FolderClass parentClass, String name) {
        if (parentClass != null && parentClass.getOtherChildren() == OtherChildren.REFUSE
                && templateNamed(parentClass, name).isEmpty()) {
            throw refused(parentClass, name);
        }
    }

    /** The template folder of this class named {@code name}, compared folded. */
    public static Optional<FolderClassChild> templateNamed(FolderClass folderClass, String name) {
        String key = SearchKey.of(name, SearchKey.NAME_LENGTH);
        List<FolderClassChild> children = folderClass.getChildren();
        return children.stream()
                .filter(child -> SearchKey.of(child.getName(), SearchKey.NAME_LENGTH).equals(key))
                .findFirst();
    }

    private static InvalidDataException refused(FolderClass parentClass, String name) {
        return new InvalidDataException("folder class id=" + parentClass.getId() + " takes no subfolder of that name",
                "folderClass.refused", name, parentClass.getTitle());
    }
}
