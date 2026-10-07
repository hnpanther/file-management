package com.hnp.filemanagement.file.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hnp.filemanagement.file.domain.FileMetadataService;
import com.hnp.filemanagement.file.domain.MetadataSearchService;
import com.hnp.filemanagement.file.persistence.MetadataSearchRepository;
import com.hnp.filemanagement.folder.domain.FolderMetadataService;
import com.hnp.filemanagement.shared.metadata.MetadataDocument;
import com.hnp.filemanagement.shared.metadata.MetadataRules;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/**
 * What v1 answers about metadata (roadmap 12.2, 12.3 - 2.13.0): the document as JSON - an object,
 * or {@code null} for none - and its entity tag, also in the {@code ETag} header, to send back as
 * {@code If-Match}.
 */
public final class MetadataAnswers {

    private MetadataAnswers() {
    }

    /**
     * A revision's metadata - a file's current, its newest revision's - and, after a write, which
     * revisions took it and whether anything changed.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FileMetadata(int fileId, String fileExternalId, int fileDetailsId, String fileDetailsExternalId,
                               int version, String fileExtension, @JsonInclude(JsonInclude.Include.ALWAYS) JsonNode metadata,
                               String etag, Boolean changed,
                               List<String> fileDetailsExternalIds) {

        static FileMetadata of(FileMetadataService.RevisionMetadata revision) {
            return new FileMetadata(revision.fileInfoId(), revision.fileExternalId(), revision.fileDetailsId(),
                    revision.fileDetailsExternalId(), revision.version(), revision.fileExtension(), revision.tree(),
                    revision.etag(), null, null);
        }

        static FileMetadata of(FileMetadataService.Written written) {
            FileMetadataService.RevisionMetadata current = written.current();
            return new FileMetadata(current.fileInfoId(), current.fileExternalId(), current.fileDetailsId(),
                    current.fileDetailsExternalId(), current.version(), current.fileExtension(), current.tree(),
                    current.etag(), written.changed(),
                    written.revisions().stream().map(FileMetadataService.RevisionMetadata::fileDetailsExternalId).toList());
        }
    }

    /** A folder's metadata. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FolderMetadata(int folderId, String name, @JsonInclude(JsonInclude.Include.ALWAYS) JsonNode metadata,
                                 String etag) {

        public static FolderMetadata of(FolderMetadataService.Described folder) {
            return new FolderMetadata(folder.folderId(), folder.name(), folder.tree(), folder.etag());
        }
    }

    /** A file a search found, with its current revision and that revision's document. */
    public record FileHit(int fileId, String fileExternalId, String fileName, int folderId, int fileDetailsId,
                          String fileDetailsExternalId, int version, String fileExtension, JsonNode metadata) {

        static FileHit of(MetadataSearchRepository.FileHit hit) {
            return new FileHit(hit.fileId(), hit.fileExternalId(), hit.fileName(), hit.folderId(), hit.fileDetailsId(),
                    hit.fileDetailsExternalId(), hit.version(), hit.fileExtension(), treeOf(hit.metadata()));
        }
    }

    /** A folder a search found, or one still to be described. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FolderHit(int folderId, String name, String displayName, JsonNode metadata, Instant createdAt) {

        public static FolderHit of(MetadataSearchRepository.FolderHit hit) {
            return new FolderHit(hit.folderId(), hit.name(), hit.displayName(), treeOf(hit.metadata()), null);
        }

        public static FolderHit of(FolderMetadataService.Undescribed folder) {
            return new FolderHit(folder.id(), folder.name(), folder.displayName(), null, folder.createdAt());
        }
    }

    /** A page of a search or a queue: the items, and whether there are more after them. */
    public record Page<T>(List<T> items, int page, int size, boolean hasNext) {

        public static <S, T> Page<T> of(MetadataSearchService.Slice<S> slice, java.util.function.Function<S, T> mapping) {
            return new Page<>(slice.items().stream().map(mapping).toList(), slice.page(), slice.size(), slice.hasNext());
        }
    }

    /** An answer with the document's tag in the {@code ETag} header as well. */
    public static <T> ResponseEntity<T> tagged(T body, String etag) {
        return ResponseEntity.ok().eTag(etag).body(body);
    }

    private static JsonNode treeOf(String document) {
        return document == null ? null : MetadataRules.treeOf(new MetadataDocument(document));
    }
}
