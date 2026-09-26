package com.hnp.filemanagement.file.domain;

/**
 * What a stored file's name is made of. It is the file's name without its last extension that
 * names a file across its versions and formats ({@code report.pdf} and {@code report.docx} are two
 * formats of the file {@code report}), and it is that part a new version must match.
 */
public final class FileNames {

    private FileNames() {
    }

    /**
     * The name without its last extension: {@code report.v2.pdf} is {@code report.v2}, and a name
     * with no extension is returned as it is.
     */
    public static String withoutExtension(String fileName) {
        return fileName.replaceFirst("[.][^.]+$", "");
    }
}
