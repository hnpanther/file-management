package com.hnp.filemanagement.dto;

/**
 * What the probe on the content-kinds page says about a sample file, without storing it.
 *
 * @param fileName      the name the sample came with
 * @param extension     its extension, lower-case, or empty
 * @param declaredType  the Content-Type the browser or client put on the part (shown, never trusted)
 * @param detectedType  what Tika makes of the first bytes; {@code application/octet-stream} when nothing fits
 * @param headHex       the first bytes, upper-case hex with spaces - the raw material for a signature
 * @param suggestedHex  the first four bytes without spaces, as the add-form's signature field wants it - a
 *                      starting point; the head shows more for a person who wants a longer one
 * @param looksLikeText no NUL byte in the first block
 * @param size          the sample's size in bytes
 * @param knownAs       the catalogued kind this extension already is, or null
 * @param bytesMatchKnown whether the bytes satisfy that kind's rule (meaningful only when {@code knownAs} is set)
 * @param refused       why this extension could never be a kind, or null
 */
public record ContentProbeDTO(String fileName, String extension, String declaredType, String detectedType,
                              String headHex, String suggestedHex, boolean looksLikeText, long size,
                              String knownAs, boolean bytesMatchKnown, String refused) {
}
