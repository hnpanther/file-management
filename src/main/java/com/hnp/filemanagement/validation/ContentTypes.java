package com.hnp.filemanagement.validation;

import com.hnp.filemanagement.exception.InvalidDataException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
 *   <li><b>The extension is a catalogue.</b> A kind is here only if it can be recognised from its
 *       bytes; anything else - no extension, or one nothing describes - is refused before a byte
 *       is stored. The catalogue has two parts: the <em>built-in</em> kinds below, with their
 *       signatures in code, and the <em>custom</em> kinds an administrator defines on the
 *       content-kinds settings page ({@code content_kind}, {@code ContentKindService}), each with
 *       a signature of its own, registered here at start-up and on every change. Which of the
 *       catalogued kinds an upload may actually use, and how large it may be, is the upload policy
 *       ({@code UploadPolicyService}). {@link #defaultExtensions()} is what that policy starts
 *       as - the nine kinds the upload form always offered.</li>
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
 * <p>Inline rendering is a further, narrower allow-list: only built-in types a browser renders
 * without running anything. Text, PDF, the images, the two media types. Never a custom kind, and
 * never SVG, HTML or XML, which {@link #isBrowserActive} refuses as custom kinds outright - each
 * can carry script, and served inline from this origin it would run against the session of
 * whoever opened the link.
 */
public final class ContentTypes {

    /** How many leading bytes a decision needs; the longest signature checked is twelve. */
    static final int HEAD_LENGTH = 8192;

    /**
     * Extensions a browser would execute as a document of this origin. They can never be a kind,
     * custom or otherwise: an attachment disposition and {@code nosniff} blunt the risk, but a
     * catalogue that simply does not contain them needs no blunting.
     */
    private static final Set<String> BROWSER_ACTIVE = Set.of("html", "htm", "xhtml", "shtml", "svg", "xml", "xsl", "xslt", "js", "mjs");

    /**
     * One kind: what it is served as, whether a browser may render it inline, how its bytes are
     * recognised, and a description of that rule for the settings page.
     *
     * @param builtIn true for the kinds in code; a custom kind is never inline-safe and can be removed
     */
    public record Kind(String extension, String mediaType, boolean inlineSafe, boolean builtIn,
                       String matchDescription, Predicate<byte[]> matches) {
    }

    /** A custom kind as the settings page defines it: a signature at an offset, or "any text". */
    public record CustomKind(String extension, String mediaType, byte[] signature, int signatureOffset, boolean textOnly) {
    }

    private static final Predicate<byte[]> ZIP = startsWith(new byte[]{'P', 'K', 0x03, 0x04});

    /** The compound-document container of the pre-2007 Office formats. */
    private static final Predicate<byte[]> OLE2 = startsWith(new byte[]{
            (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1});

    /** Catalogue order is the order the settings pages list them in. */
    private static final Map<String, Kind> BUILT_IN;

    /** The nine kinds the upload form always offered; the upload policy starts as exactly these. */
    private static final Set<String> DEFAULTS = Set.of("pdf", "png", "jpg", "jpeg", "docx", "xlsx", "pptx", "mp4", "mp3", "txt");

    /** The administrator's kinds, replaced wholesale by {@link #registerCustom}; never inline-safe. */
    private static volatile Map<String, Kind> custom = Map.of();

    static {
        Map<String, Kind> kinds = new LinkedHashMap<>();
        builtIn(kinds, "pdf", "application/pdf", true, "starts with %PDF-", startsWith("%PDF-"));
        builtIn(kinds, "png", "image/png", true, "starts with 89 50 4E 47 0D 0A 1A 0A", startsWith(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}));
        builtIn(kinds, "jpg", "image/jpeg", true, "starts with FF D8 FF", startsWith(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}));
        builtIn(kinds, "jpeg", "image/jpeg", true, "starts with FF D8 FF", startsWith(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}));
        builtIn(kinds, "gif", "image/gif", true, "starts with GIF8", startsWith("GIF8"));
        builtIn(kinds, "txt", "text/plain", true, "text: no NUL byte in the first 8 KB", ContentTypes::looksLikeText);
        builtIn(kinds, "csv", "text/csv", false, "text: no NUL byte in the first 8 KB", ContentTypes::looksLikeText);
        builtIn(kinds, "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", false, "ZIP container (PK 03 04)", ZIP);
        builtIn(kinds, "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", false, "ZIP container (PK 03 04)", ZIP);
        builtIn(kinds, "pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation", false, "ZIP container (PK 03 04)", ZIP);
        builtIn(kinds, "doc", "application/msword", false, "OLE2 container (D0 CF 11 E0 A1 B1 1A E1)", OLE2);
        builtIn(kinds, "xls", "application/vnd.ms-excel", false, "OLE2 container (D0 CF 11 E0 A1 B1 1A E1)", OLE2);
        builtIn(kinds, "ppt", "application/vnd.ms-powerpoint", false, "OLE2 container (D0 CF 11 E0 A1 B1 1A E1)", OLE2);
        builtIn(kinds, "mp4", "video/mp4", true, "ftyp at offset 4", head -> head.length >= 12
                && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p');
        builtIn(kinds, "mp3", "audio/mpeg", true, "ID3 tag or MPEG frame sync", head -> head.length >= 3
                && ((head[0] == 'I' && head[1] == 'D' && head[2] == '3')
                || ((head[0] & 0xFF) == 0xFF && (head[1] & 0xE0) == 0xE0)));
        builtIn(kinds, "zip", "application/zip", false, "starts with PK 03 04", ZIP);
        builtIn(kinds, "rar", "application/vnd.rar", false, "starts with Rar!", startsWith("Rar!"));
        builtIn(kinds, "7z", "application/x-7z-compressed", false, "starts with 37 7A BC AF 27 1C", startsWith(new byte[]{'7', 'z', (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C}));
        BUILT_IN = Collections.unmodifiableMap(kinds);
    }

    private static void builtIn(Map<String, Kind> into, String extension, String mediaType, boolean inlineSafe,
                                String description, Predicate<byte[]> matches) {
        into.put(extension, new Kind(extension, mediaType, inlineSafe, true, description, matches));
    }

    private ContentTypes() {
    }

    // ---------------------------------------------------------------- the catalogue

    /** Every kind, built-in first in catalogue order, then the custom ones as registered. */
    public static Set<String> knownExtensions() {
        Set<String> all = new LinkedHashSet<>(BUILT_IN.keySet());
        all.addAll(custom.keySet());
        return Collections.unmodifiableSet(all);
    }

    /** The kinds an installation allows until somebody edits the policy. */
    public static Set<String> defaultExtensions() {
        return DEFAULTS;
    }

    /** Every kind with its description, in the same order as {@link #knownExtensions()}. */
    public static List<Kind> kinds() {
        List<Kind> all = new java.util.ArrayList<>(BUILT_IN.values());
        all.addAll(custom.values());
        return Collections.unmodifiableList(all);
    }

    public static Optional<Kind> kindOf(String extension) {
        return Optional.ofNullable(lookup(extension));
    }

    public static boolean isBuiltIn(String extension) {
        return extension != null && BUILT_IN.containsKey(extension.toLowerCase(Locale.ROOT));
    }

    /** Whether a browser would run this extension as a document; such a kind is never admitted. */
    public static boolean isBrowserActive(String extension) {
        return extension != null && BROWSER_ACTIVE.contains(extension.toLowerCase(Locale.ROOT));
    }

    /**
     * Replaces the custom kinds with these. Called by {@code ContentKindService} at start-up and
     * after every change, so the registry is what the table says. A custom kind that names a
     * built-in extension or a browser-active one is skipped - the service refuses to store such a
     * row, and this is the second line.
     */
    public static void registerCustom(Collection<CustomKind> kinds) {
        Map<String, Kind> replacement = new LinkedHashMap<>();
        for (CustomKind kind : kinds) {
            String extension = kind.extension().toLowerCase(Locale.ROOT);
            if (BUILT_IN.containsKey(extension) || BROWSER_ACTIVE.contains(extension)) {
                continue;
            }
            Predicate<byte[]> matches;
            String description;
            if (kind.textOnly()) {
                matches = ContentTypes::looksLikeText;
                description = "text: no NUL byte in the first 8 KB";
            } else {
                matches = startsWith(kind.signature(), kind.signatureOffset());
                description = (kind.signatureOffset() == 0 ? "starts with " : "at offset " + kind.signatureOffset() + ": ")
                        + HexFormat.ofDelimiter(" ").withUpperCase().formatHex(kind.signature());
            }
            replacement.put(extension, new Kind(extension, kind.mediaType(), false, false, description, matches));
        }
        custom = Collections.unmodifiableMap(replacement);
    }

    /** The media type a catalogued extension stands for, for the settings page. */
    public static Optional<String> mediaTypeOf(String extension) {
        return servedTypeFor(extension);
    }

    // ---------------------------------------------------------------- deciding

    /**
     * The media type to store for an upload, or an {@link InvalidDataException} saying why it is
     * refused. The client's declared type is not consulted.
     */
    public static String detect(MultipartFile file) {
        String name = file.getOriginalFilename();
        String extension = extensionOf(name);
        Kind kind = lookup(extension);
        if (kind == null) {
            throw new InvalidDataException("file type ." + extension + " is not recognised; recognised: "
                    + String.join(", ", knownExtensions()),
                    "upload.invalid.typeNotRecognised", extension, String.join(", ", knownExtensions()));
        }
        byte[] head = head(file);
        if (!kind.matches().test(head)) {
            throw new InvalidDataException("the content of " + name + " is not a ." + extension + " file",
                    "upload.invalid.contentMismatch", name, extension);
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
        Kind kind = lookup(extension);
        return kind == null ? Optional.empty() : Optional.of(kind.mediaType());
    }

    /** Whether a download of this extension may be shown inline rather than saved. */
    public static boolean inlineSafe(String extension) {
        Kind kind = lookup(extension);
        return kind != null && kind.inlineSafe();
    }

    /** The first bytes of a file, for the probe; the same block {@link #detect} judges. */
    public static byte[] headOf(MultipartFile file) {
        return head(file);
    }

    /** The extension of a name, lower-case, or empty. */
    public static String extensionOf(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** No NUL byte in the first block. Empty is text too: an empty note is a legitimate document. */
    public static boolean looksLikeText(byte[] head) {
        for (byte b : head) {
            if (b == 0) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- pieces

    private static Kind lookup(String extension) {
        if (extension == null) {
            return null;
        }
        String key = extension.toLowerCase(Locale.ROOT);
        Kind kind = BUILT_IN.get(key);
        return kind != null ? kind : custom.get(key);
    }

    private static byte[] head(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return in.readNBytes(HEAD_LENGTH);
        } catch (IOException e) {
            throw new InvalidDataException("cannot read the uploaded file: " + e.getMessage());
        }
    }

    private static Predicate<byte[]> startsWith(String ascii) {
        return startsWith(ascii.getBytes(StandardCharsets.US_ASCII));
    }

    private static Predicate<byte[]> startsWith(byte[] signature) {
        return startsWith(signature, 0);
    }

    private static Predicate<byte[]> startsWith(byte[] signature, int offset) {
        return head -> {
            if (head.length < offset + signature.length) {
                return false;
            }
            for (int i = 0; i < signature.length; i++) {
                if (head[offset + i] != signature[i]) {
                    return false;
                }
            }
            return true;
        };
    }
}
