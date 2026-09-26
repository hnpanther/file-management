package com.hnp.filemanagement.shared.web;

/**
 * Body of the file-info description update: {@code {"description": "..."}}.
 *
 * @param description the new description; emptiness is rejected by the service
 */
public record DescriptionUpdateRequest(String description) {
}
