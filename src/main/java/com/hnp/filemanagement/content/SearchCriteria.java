package com.hnp.filemanagement.content;

import java.time.Instant;
import java.util.List;

/**
 * What an advanced search asks (roadmap 12.8): any of these, all of them together. Made by
 * {@link ContentSearchService} from what a person typed, every part already checked and in the form a
 * query compares - nothing here is text that reaches SQL as syntax.
 *
 * @param text           words in the text inside the files, or null
 * @param nameKey        a fragment of the file's name, folded as names are and escaped for {@code LIKE}, or null
 * @param folderPath     a folder's materialised path ({@code /1/7/22/}): the files in it and below it, or null
 * @param metadata       the file's own document must hold each row - each row a list of documents, any one of
 *                       which will do (a value typed {@code 1500} matches the number and the string) - or empty
 * @param folderMetadata one folder above the file, readable by the reader, must hold every row - or empty
 * @param askedKeys      the keys of the file's metadata asked, for a result to show what it holds for them
 * @param extensions     the file's kind, lower case, any of them - or empty
 * @param from           uploaded at or after, or null
 * @param until          uploaded before, or null
 * @param allVersions    every version of a file, or its latest only (every format of it)
 * @param sort           the order of the results
 */
public record SearchCriteria(ContentQuery text, String nameKey, String folderPath, List<List<String>> metadata,
                             List<List<String>> folderMetadata, List<String> askedKeys, List<String> extensions,
                             Instant from, Instant until, boolean allVersions, Sort sort) {

    /** The order of the results. */
    public enum Sort {
        /** The best match first - with words; without them, the newest. */
        RELEVANCE,
        /** The newest revision first. */
        NEWEST,
        /** The oldest revision first. */
        OLDEST
    }

    public SearchCriteria {
        metadata = metadata == null ? List.of() : List.copyOf(metadata);
        folderMetadata = folderMetadata == null ? List.of() : List.copyOf(folderMetadata);
        askedKeys = askedKeys == null ? List.of() : List.copyOf(askedKeys);
        extensions = extensions == null ? List.of() : List.copyOf(extensions);
        sort = sort == null ? Sort.RELEVANCE : sort;
    }

    /** Words only, in every folder, the latest versions - the search of 2.15.0. */
    public static SearchCriteria ofText(ContentQuery text, boolean allVersions) {
        return new SearchCriteria(text, null, null, List.of(), List.of(), List.of(), List.of(), null, null, allVersions,
                Sort.RELEVANCE);
    }

    public boolean hasText() {
        return text != null;
    }

    /** Whether anything narrows the files, besides the words, the versions and the reader's scope. */
    public boolean hasFileFilters() {
        return nameKey != null || folderPath != null || !metadata.isEmpty() || !folderMetadata.isEmpty()
                || !extensions.isEmpty() || from != null || until != null;
    }

    /** Whether anything at all is asked. */
    public boolean asksAnything() {
        return hasText() || hasFileFilters();
    }

    @Override
    public String toString() {
        // Never the words, the name or the documents - any may be personal.
        return "SearchCriteria[text=" + hasText() + ", name=" + (nameKey != null) + ", folder=" + (folderPath != null)
                + ", metadata=" + metadata.size() + ", folderMetadata=" + folderMetadata.size() + ", kinds="
                + extensions.size() + ", dates=" + (from != null || until != null) + ", allVersions=" + allVersions
                + ", sort=" + sort + "]";
    }
}
