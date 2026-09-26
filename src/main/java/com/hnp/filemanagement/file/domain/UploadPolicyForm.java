package com.hnp.filemanagement.file.domain;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * What the upload-policy table posts: the kinds that were ticked, and a limit in megabytes for
 * every kind on the page (ticked or not - an unticked kind's number is kept in the form so it
 * survives a round trip, and ignored on save).
 *
 * <p>{@code allowed} is deliberately not {@code @NotNull}: a browser omits a checkbox group with
 * nothing ticked, and "nothing may be uploaded" is a legitimate policy to save.
 */
@Data
public class UploadPolicyForm {

    /** The ticked extensions. */
    private List<String> allowed;

    /** Extension → limit in megabytes, as {@code max[pdf]=20}. */
    private Map<String, Long> max;
}
