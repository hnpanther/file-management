package com.hnp.filemanagement.file.domain;

import com.hnp.filemanagement.file.persistence.MetadataSearchRepository;
import com.hnp.filemanagement.folder.domain.FolderAccessService;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.shared.metadata.MetadataDocument;
import com.hnp.filemanagement.shared.metadata.MetadataRules;
import com.hnp.filemanagement.shared.web.PageRequests;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Search by metadata (roadmap 12.2 step 3, 12.3 step 4): what is asked is itself a document - a JSON
 * object, checked by the same rules - and a hit is whatever holds it ({@code @>}): every key it names,
 * with that value; a nested object, part of one; an array, among its members. Only what the reader
 * may read; a page at a time, newest first, as a slice.
 */
@Service
public class MetadataSearchService {

    /** The rows a page holds when none is asked for. */
    public static final int DEFAULT_PAGE_SIZE = 50;

    /** A page of hits, and whether there are more. */
    public record Slice<T>(List<T> items, int page, int size, boolean hasNext) {
    }

    private final MetadataSearchRepository repository;
    private final FolderAccessService folderAccessService;
    private final MetadataRules metadataRules;

    public MetadataSearchService(MetadataSearchRepository repository, FolderAccessService folderAccessService,
                                 MetadataRules metadataRules) {
        this.repository = repository;
        this.folderAccessService = folderAccessService;
        this.metadataRules = metadataRules;
    }

    /**
     * Files whose current document holds {@code metadata}, below a folder whose document holds
     * {@code folderMetadata}, or both; at least one is asked.
     */
    @Transactional(readOnly = true)
    public Slice<MetadataSearchRepository.FileHit> files(String metadata, String folderMetadata, Integer page, Integer size,
                                                          int principalId) {
        Optional<MetadataDocument> document = metadataRules.parse(metadata);
        Optional<MetadataDocument> folderDocument = metadataRules.parse(folderMetadata);
        if (document.isEmpty() && folderDocument.isEmpty()) {
            throw new InvalidDataException("a search by metadata asks for metadata or folderMetadata", "metadata.search.empty");
        }
        int rows = PageRequests.size(size, DEFAULT_PAGE_SIZE);
        int number = PageRequests.number(page);
        var hits = repository.files(MetadataDocument.columnOf(document), MetadataDocument.columnOf(folderDocument),
                folderAccessService.readScope(principalId), number, rows);
        return sliceOf(hits, number, rows);
    }

    /** Folders whose document holds {@code metadata}. */
    @Transactional(readOnly = true)
    public Slice<MetadataSearchRepository.FolderHit> folders(String metadata, Integer page, Integer size, int principalId) {
        Optional<MetadataDocument> document = metadataRules.parse(metadata);
        if (document.isEmpty()) {
            throw new InvalidDataException("a search by metadata asks for metadata", "metadata.search.empty");
        }
        int rows = PageRequests.size(size, DEFAULT_PAGE_SIZE);
        int number = PageRequests.number(page);
        var hits = repository.folders(document.get().json(), folderAccessService.readScope(principalId), number, rows);
        return sliceOf(hits, number, rows);
    }

    private static <T> Slice<T> sliceOf(List<T> hits, int page, int size) {
        boolean hasNext = hits.size() > size;
        return new Slice<>(hasNext ? hits.subList(0, size) : hits, page, size, hasNext);
    }
}
