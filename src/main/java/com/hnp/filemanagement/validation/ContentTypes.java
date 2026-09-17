package com.hnp.filemanagement.validation;

import com.hnp.filemanagement.exception.InvalidDataException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * What may be stored, what it is called, and what may be shown inline - decided by the server from
 * the file's extension and its first bytes, never from anything the client declared
 * ({@code docs/issues.md}, issues 12 and 13).
 *
 * <p>Three rules, in one place so that the web form, the v1 API and the v2 API cannot drift:
 *
 * <ul>
 *   <li><b>The extension is a catalogue.</b> A kind is here only if this class can recognise it
 *       from its bytes; anything else - {@code .html}, {@code .svg}, {@code .exe}, no extension -
 *       is refused before a byte is stored, and no setting can admit it. Which of the catalogued
 *       kinds an upload may actually use, and how large it may be, is the upload policy
 *       ({@code UploadPolicyService}): a system-wide list the administrator edits, overridable per
 *       role. {@link #defaultExtensions()} is what that list starts as - the nine kinds the upload
 *       form always offered - so an installation that never opens the settings page behaves as it
 *       always did.</li>
 *   <li><b>The bytes must be what the extension says.</b> A PDF starts with {@code %PDF-}, a PNG
 *       with its eight-byte signature, an Office document with a ZIP header, and so on. A renamed
 *       executable is refused whatever it is called. Text is the one kind with no signature; it is
 *       accepted if its first block holds no NUL byte, which is what tells text from a binary.</li>
 *   <li><b>The served type comes from the extension, not from the row.</b> Whatever
 *       {@code file_details.content_type} holds - and until {@code V2.5} it held whatever the
 *       client sent - a download is answered with the type this class maps the extension to. A
 *       row nothing here recognises is served as {@code application/octet-stream}, as an
 *       attachment: the browser is told to save it and nothing more.</li>
 * </ul>
 *
 * <p>Inline rendering is a further, narrower allow-list: only types a browser renders without
 * running anything. Text, PDF, the two images, the two media types. Not SVG, not HTML, not XML -
 * each can carry script, and served inline from this origin it would run against the session of
 * whoever opened the link.
 */
public final class ContentTypes {

    /** How many leading bytes a decision needs; the longest signature checked is twelve. */
    static final int HEAD_LENGTH = 8192;

    private record Kind(String mediaType, boolean inlineSafe, Predicate<byte[]> matches) {
    }

    private static final Predicate<byte[]> ZIP = startsWith(new byte[]{'P', 'K', 0x03, 0x04});

    /** The compound-document container of the pre-2007 Office formats. */
    private static final Predicate<byte[]> OLE2 = startsWith(new byte[]{
            (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1});

    /** Catalogue order is the order the settings page lists them in. */
    private static final Map<String, Kind> BY_EXTENSION;

    /** The nine kinds the upload form always offered; the upload policy starts as exactly these. */
    private static final Set<String> DEFAULTS = Set.of("pdf", "png", "jpg", "jpeg", "docx", "xlsx", "pptx", "mp4", "mp3", "txt");

    static {
        Map<String, Kind> kinds = new java.util.LinkedHashMap<>();
        kinds.put("pdf", new Kind("application/pdf", true, startsWith("%PDF-")));
        kinds.put("png", new Kind("image/png", true, startsWith(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A})));
        kinds.put("jpg", new Kind("image/jpeg", true, startsWith(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})));
        kinds.put("jpeg", new Kind("image/jpeg", true, startsWith(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})));
        kinds.put("gif", new Kind("image/gif", true, startsWith("GIF8")));
        kinds.put("txt", new Kind("text/plain", true, ContentTypes::looksLikeText));
        kinds.put("csv", new Kind("text/csv", false, ContentTypes::looksLikeText));
        kinds.put("docx", new Kind("application/vnd.openxmlformats-officedocument.wordprocessingml.document", false, ZIP));
        kinds.put("xlsx", new Kind("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", false, ZIP));
        kinds.put("pptx", new Kind("application/vnd.openxmlformats-officedocument.presentationml.presentation", false, ZIP));
        kinds.put("doc", new Kind("application/msword", false, OLE2));
        kinds.put("xls", new Kind("application/vnd.ms-excel", false, OLE2));
        kinds.put("ppt", new Kind("application/vnd.ms-powerpoint", false, OLE2));
        kinds.put("mp4", new Kind("video/mp4", true, head -> head.length >= 12
                && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p'));
        kinds.put("mp3", new Kind("audio/mpeg", true, head -> head.length >= 3
                && ((head[0] == 'I' && head[1] == 'D' && head[2] == '3')
                || ((head[0] & 0xFF) == 0xFF && (head[1] & 0xE0) == 0xE0))));
        kinds.put("zip", new Kind("application/zip", false, ZIP));
        kinds.put("rar", new Kind("application/vnd.rar", false, startsWith("Rar!")));
        kinds.put("7z", new Kind("application/x-7z-compressed", false, startsWith(new byte[]{'7', 'z', (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C})));
        BY_EXTENSION = java.util.Collections.unmodifiableMap(kinds);
    }

    private ContentTypes() {
    }

    /** Every kind this class can recognise, lower-case, in catalogue order - what a policy may choose from. */
    public static Set<String> knownExtensions() {
        return BY_EXTENSION.keySet();
    }

    /** The kinds an installation allows until somebody edits the policy. */
    public static Set<String> defaultExtensions() {
        return DEFAULTS;
    }

    /** The media type a catalogued extension stands for, for the settings page. */
    public static Optional<String> mediaTypeOf(String extension) {
        return servedTypeFor(extension);
    }

    /**
     * The media type to store for an upload, or an {@link InvalidDataException} saying why it is
     * refused. The client's declared type is not consulted.
     */
    public static String detect(MultipartFile file) {
        String name = file.getOriginalFilename();
        String extension = extensionOf(name);
        Kind kind = BY_EXTENSION.get(extension);
        if (kind == null) {
            throw new InvalidDataException("file type ." + extension + " is not recognised; recognised: "
                    + String.join(", ", knownExtensions()));
        }
        byte[] head = head(file);
        if (!kind.matches().test(head)) {
            throw new InvalidDataException("the content of " + name + " is not a ." + extension + " file");
        }
        return kind.mediaType();
    }

    /** Whether {@link #detect} would accept this file - the bean-validation face of the same rule. */
    public static boolean isAllowed(MultipartFile file) {
        try {
            detect(file);
            return true;
        } catch (InvalidDataException e) {
            return false;
        }
    }

    /** The type a download is served with, from the extension alone. Empty for anything unknown. */
    public static Optional<String> servedTypeFor(String extension) {
        Kind kind = extension == null ? null : BY_EXTENSION.get(extension.toLowerCase(Locale.ROOT));
        return kind == null ? Optional.empty() : Optional.of(kind.mediaType());
    }

    /** Whether a download of this extension may be shown inline rather than saved. */
    public static boolean inlineSafe(String extension) {
        Kind kind = extension == null ? null : BY_EXTENSION.get(extension.toLowerCase(Locale.ROOT));
        return kind != null && kind.inlineSafe();
    }

    // ---------------------------------------------------------------- pieces

    private static String extensionOf(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static byte[] head(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return in.readNBytes(HEAD_LENGTH);
        } catch (IOException e) {
            throw new InvalidDataException("cannot read the uploaded file: " + e.getMessage());
        }
    }

    private static Predicate<byte[]> startsWith(String ascii) {
        return startsWith(ascii.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static Predicate<byte[]> startsWith(byte[] signature) {
        return head -> {
            if (head.length < signature.length) {
                return false;
            }
            for (int i = 0; i < signature.length; i++) {
                if (head[i] != signature[i]) {
                    return false;
                }
            }
            return true;
        };
    }

    /** No NUL byte in the first block. Empty is text too: an empty note is a legitimate document. */
    private static boolean looksLikeText(byte[] head) {
        for (byte b : head) {
            if (b == 0) {
                return false;
            }
        }
        return true;
    }
}
