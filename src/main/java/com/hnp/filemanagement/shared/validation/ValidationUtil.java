package com.hnp.filemanagement.shared.validation;

import java.util.Set;

/**
 * The two naming rules that stand between a caller-supplied string and a path on disk.
 *
 * <p>Both were once "no dot, no space, no slash": a folder name was a directory and a file name
 * became one, and the storage layer built paths by concatenation. Since {@code V2.9} a folder's
 * name is not on disk at all, a file's name still is - as the directory holding its versions
 * and as the stored file - and every path is resolved and contained by
 * {@code FilesystemBlobStore.within}. What is left to refuse is what a file system
 * itself refuses, or what would leave the directory the name was meant for: separators, the
 * two dot-names, control characters, the characters Windows forbids, a trailing dot or space
 * (which Windows strips, making two names one), and the names Windows reserves. Spaces, dots
 * inside the name, and any script - Persian included - are fine.
 */
public final class ValidationUtil {

    private static final String FORBIDDEN = "<>:\"|?*";

    /** Windows refuses these as file or directory names, with or without an extension, in any case. */
    private static final Set<String> RESERVED = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private ValidationUtil() {
    }

    /**
     * Whether a string may be one segment of a path: a folder name, or the directory a file's
     * versions live in. Empty is refused - a name that is nothing lands in the parent.
     */
    public static boolean checkCorrectDirectoryName(String directoryName) {
        return isSafeSegment(directoryName);
    }

    /**
     * Whether a string may be a stored file's name: a safe segment with an extension - at least
     * one dot with something on both sides, the extension letters and digits only, since it is
     * what the content catalogue is looked up by.
     */
    public static boolean checkCorrectFileName(String fileName) {
        if (!isSafeSegment(fileName)) {
            return false;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) {
            return false;
        }
        String extension = fileName.substring(dot + 1);
        return extension.chars().allMatch(c -> Character.isLetterOrDigit(c) && c < 128);
    }

    private static boolean isSafeSegment(String name) {
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            return false;
        }
        if (name.startsWith(" ") || name.endsWith(" ") || name.endsWith(".")) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '/' || c == '\\' || c < 0x20 || c == 0x7f || FORBIDDEN.indexOf(c) >= 0) {
                return false;
            }
        }
        int dot = name.indexOf('.');
        String stem = dot < 0 ? name : name.substring(0, dot);
        return !RESERVED.contains(stem.toUpperCase());
    }
}
