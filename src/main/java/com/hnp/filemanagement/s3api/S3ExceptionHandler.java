package com.hnp.filemanagement.s3api;

import com.hnp.filemanagement.shared.exception.BusinessException;
import com.hnp.filemanagement.shared.exception.DuplicateResourceException;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.shared.exception.ResourceNotFoundException;
import com.hnp.filemanagement.shared.exception.StorageUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.io.IOException;

/**
 * Every failure of the S3 surface as S3's XML (roadmap 9.10.4), ahead of the application's own
 * handler, which answers the pages and v1 differently. Nothing here leaks a path on disk or a stack.
 */
@RestControllerAdvice(assignableTypes = S3Controller.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class S3ExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(S3ExceptionHandler.class);

    @ExceptionHandler(S3ObjectService.NotFound.class)
    ResponseEntity<String> notFound(S3ObjectService.NotFound e, HttpServletRequest request) {
        return answer(e.bucket() ? S3Errors.Error.NO_SUCH_BUCKET : S3Errors.Error.NO_SUCH_KEY, null, request);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<String> resourceNotFound(ResourceNotFoundException e, HttpServletRequest request) {
        return answer(S3Errors.Error.NO_SUCH_KEY, null, request);
    }

    @ExceptionHandler(S3ObjectService.PreconditionFailed.class)
    ResponseEntity<String> precondition(S3ObjectService.PreconditionFailed e, HttpServletRequest request) {
        return answer(S3Errors.Error.PRECONDITION_FAILED, null, request);
    }

    @ExceptionHandler(S3ObjectService.FolderNotEmpty.class)
    ResponseEntity<String> folderNotEmpty(S3ObjectService.FolderNotEmpty e, HttpServletRequest request) {
        return answer(S3Errors.Error.FOLDER_NOT_EMPTY, null, request);
    }

    @ExceptionHandler(S3ObjectService.NotImplementedHere.class)
    ResponseEntity<String> notImplemented(S3ObjectService.NotImplementedHere e, HttpServletRequest request) {
        return answer(S3Errors.Error.NOT_IMPLEMENTED, e.getMessage(), request);
    }

    @ExceptionHandler(S3MultipartService.NoSuchUpload.class)
    ResponseEntity<String> noSuchUpload(S3MultipartService.NoSuchUpload e, HttpServletRequest request) {
        return answer(S3Errors.Error.NO_SUCH_UPLOAD, null, request);
    }

    @ExceptionHandler(S3MultipartService.InvalidPart.class)
    ResponseEntity<String> invalidPart(S3MultipartService.InvalidPart e, HttpServletRequest request) {
        return answer(S3Errors.Error.INVALID_PART, e.getMessage(), request);
    }

    @ExceptionHandler(S3MultipartService.InvalidPartOrder.class)
    ResponseEntity<String> invalidPartOrder(S3MultipartService.InvalidPartOrder e, HttpServletRequest request) {
        return answer(S3Errors.Error.INVALID_PART_ORDER, e.getMessage(), request);
    }

    @ExceptionHandler(S3MultipartService.BadDigest.class)
    ResponseEntity<String> badDigest(S3MultipartService.BadDigest e, HttpServletRequest request) {
        return answer(S3Errors.Error.BAD_DIGEST, null, request);
    }

    @ExceptionHandler(S3Metadata.TooLarge.class)
    ResponseEntity<String> metadataTooLarge(S3Metadata.TooLarge e, HttpServletRequest request) {
        return answer(S3Errors.Error.METADATA_TOO_LARGE, null, request);
    }

    @ExceptionHandler(S3Xml.MalformedXml.class)
    ResponseEntity<String> malformed(S3Xml.MalformedXml e, HttpServletRequest request) {
        return answer(S3Errors.Error.MALFORMED_XML, null, request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<String> denied(AccessDeniedException e, HttpServletRequest request) {
        return answer(S3Errors.Error.ACCESS_DENIED, e.getMessage(), request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<String> tooLarge(MaxUploadSizeExceededException e, HttpServletRequest request) {
        return answer(S3Errors.Error.ENTITY_TOO_LARGE, null, request);
    }

    @ExceptionHandler(StorageUnavailableException.class)
    ResponseEntity<String> storage(StorageUnavailableException e, HttpServletRequest request) {
        return answer(S3Errors.Error.SERVICE_UNAVAILABLE, null, request);
    }

    @ExceptionHandler(S3Controller.ContentHashMismatch.class)
    ResponseEntity<String> hashMismatch(S3Controller.ContentHashMismatch e, HttpServletRequest request) {
        return answer(S3Errors.Error.CONTENT_SHA256_MISMATCH, null, request);
    }

    /** A chunk or trailer that failed its signature or its checksum, or a body cut short. */
    @ExceptionHandler(IOException.class)
    ResponseEntity<String> body(IOException e, HttpServletRequest request) {
        logger.info("s3 request body refused: {}", e.getMessage());
        return answer(S3Errors.Error.INCOMPLETE_BODY, null, request);
    }

    /** What the application refuses - a name, a kind, bytes that are not their extension, a depth. */
    @ExceptionHandler({InvalidDataException.class, DuplicateResourceException.class, BusinessException.class})
    ResponseEntity<String> invalid(RuntimeException e, HttpServletRequest request) {
        return answer(S3Errors.Error.INVALID_ARGUMENT, e.getMessage(), request);
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<String> unexpected(RuntimeException e, HttpServletRequest request) {
        logger.error("s3 request failed: " + request.getMethod() + " " + request.getRequestURI(), e);
        return answer(S3Errors.Error.INTERNAL_ERROR, null, request);
    }

    private static ResponseEntity<String> answer(S3Errors.Error error, String message, HttpServletRequest request) {
        return ResponseEntity.status(error.status()).contentType(MediaType.APPLICATION_XML)
                .body(S3Errors.body(error, message == null ? error.message() : message, request.getRequestURI()));
    }
}
