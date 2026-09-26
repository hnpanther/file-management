package com.hnp.filemanagement.file.domain;

/**
 * One line of the upload-policy table as a page renders it: a catalogued kind, whether it is
 * allowed, and its limit in whole megabytes.
 *
 * @param extension the kind, lower-case without the dot
 * @param mediaType what the kind is served as, shown beside it
 * @param allowed   whether the policy on screen lists it
 * @param maxMb     the limit, in megabytes; the default for a kind not listed
 */
public record UploadRuleRowDTO(String extension, String mediaType, boolean allowed, long maxMb) {
}
