package com.hnp.filemanagement.dto;

/**
 * Body of the "enable / disable user" call: {@code {"enabled": 1}}.
 *
 * @param enabled 1 to enable, 0 to disable; validated by the service, not here
 */
public record EnabledChangeRequest(Integer enabled) {
}
