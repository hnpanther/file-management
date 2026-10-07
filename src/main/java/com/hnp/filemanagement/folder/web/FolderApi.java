package com.hnp.filemanagement.folder.web;

import com.hnp.filemanagement.file.domain.MetadataSearchService;
import com.hnp.filemanagement.file.web.MetadataAnswers;
import com.hnp.filemanagement.folder.domain.FolderMetadataService;
import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.shared.metadata.MetadataPrecondition;
import com.hnp.filemanagement.shared.web.GlobalGeneralLogging;
import com.hnp.filemanagement.shared.web.PageRequests;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Folders on API v1 (roadmap 12.3 - 2.13.0): a folder's metadata - read, set where there is none,
 * replaced, cleared - the queue of its children an upload made that nobody has described, and a
 * search by metadata. A folder is named by the id v1's upload takes ({@code folderId}). The same
 * account or key as {@code /api/v1/files}, under the same folder access; the guide is
 * {@code docs/api-v1.md}.
 */
@RestController
@RequestMapping("/api/v1/folders")
public class FolderApi {

    private final GlobalGeneralLogging globalGeneralLogging;
    private final FolderMetadataService folderMetadataService;
    private final MetadataSearchService metadataSearchService;

    public FolderApi(GlobalGeneralLogging globalGeneralLogging, FolderMetadataService folderMetadataService,
                     MetadataSearchService metadataSearchService) {
        this.globalGeneralLogging = globalGeneralLogging;
        this.folderMetadataService = folderMetadataService;
        this.metadataSearchService = metadataSearchService;
    }

    /** A folder's metadata and its entity tag, in the body and the {@code ETag} header. {@code READ} on the folder. */
    // API_GET_METADATA
    @PreAuthorize("hasAuthority('API_GET_METADATA') || hasAuthority('ADMIN')")
    @GetMapping("{folderId}/metadata")
    public ResponseEntity<MetadataAnswers.FolderMetadata> metadata(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                                                   @PathVariable("folderId") int folderId) {
        globalGeneralLogging.detail("get metadata of folder id=" + folderId);
        var answer = MetadataAnswers.FolderMetadata.of(folderMetadataService.of(folderId, userDetails.getId()));
        return MetadataAnswers.tagged(answer, answer.etag());
    }

    /**
     * Sets, replaces or clears a folder's metadata - the body a JSON object, {@code {}} to clear;
     * conditioned by {@code If-None-Match: *} or {@code If-Match} as a file's. {@code WRITE} on the
     * folder; never the root or {@code Profiles}; a personal folder only its user's.
     */
    // API_SET_METADATA
    @PreAuthorize("hasAuthority('API_SET_METADATA') || hasAuthority('ADMIN')")
    @PutMapping("{folderId}/metadata")
    public ResponseEntity<MetadataAnswers.FolderMetadata> setMetadata(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                                                      @PathVariable("folderId") int folderId,
                                                                      @RequestBody(required = false) String body,
                                                                      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                                      @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        globalGeneralLogging.detail("set metadata of folder id=" + folderId);
        if (body == null || body.isBlank()) {
            throw new InvalidDataException("the body is the metadata, a JSON object - {} to clear it", "metadata.invalid.notObject");
        }
        var answer = MetadataAnswers.FolderMetadata.of(folderMetadataService.replace(folderId, body,
                new MetadataPrecondition(ifMatch, ifNoneMatch), userDetails.getId()));
        return MetadataAnswers.tagged(answer, answer.etag());
    }

    /**
     * The folder's children without metadata, newest first - what is still to be described; only
     * those the caller can see. {@code size} at most 200, a page with {@code hasNext} and no count.
     */
    // API_GET_METADATA (the queue)
    @PreAuthorize("hasAuthority('API_GET_METADATA') || hasAuthority('ADMIN')")
    @GetMapping("{folderId}/undescribed")
    public MetadataAnswers.Page<MetadataAnswers.FolderHit> undescribed(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                                                      @PathVariable("folderId") int folderId,
                                                                      @RequestParam(value = "page", required = false) Integer page,
                                                                      @RequestParam(value = "size", required = false) Integer size) {
        globalGeneralLogging.detail("folders without metadata under folder id=" + folderId + " page=" + page);
        int rows = PageRequests.size(size, MetadataSearchService.DEFAULT_PAGE_SIZE);
        var slice = folderMetadataService.undescribedUnder(folderId, PageRequests.number(page), rows, userDetails.getId());
        return new MetadataAnswers.Page<>(slice.getContent().stream().map(MetadataAnswers.FolderHit::of).toList(),
                slice.getNumber(), rows, slice.hasNext());
    }

    /** Folders whose metadata holds {@code metadata}, a JSON object; only those the caller may read; newest first. */
    // API_SEARCH_METADATA
    @PreAuthorize("hasAuthority('API_SEARCH_METADATA') || hasAuthority('ADMIN')")
    @GetMapping("search")
    public MetadataAnswers.Page<MetadataAnswers.FolderHit> search(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                                                 @RequestParam(value = "metadata", required = false) String metadata,
                                                                 @RequestParam(value = "page", required = false) Integer page,
                                                                 @RequestParam(value = "size", required = false) Integer size) {
        globalGeneralLogging.detail("search folders by metadata page=" + page);
        return MetadataAnswers.Page.of(metadataSearchService.folders(metadata, page, size, userDetails.getId()),
                MetadataAnswers.FolderHit::of);
    }
}
