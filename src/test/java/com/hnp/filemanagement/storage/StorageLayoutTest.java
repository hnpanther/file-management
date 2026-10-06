package com.hnp.filemanagement.storage;

import com.hnp.filemanagement.folder.domain.FolderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The layout, pinned as a pure unit test: this is the one place the shape of a new revision's key
 * is written, and the one place a file's directory is read back off a key - a change here changes
 * where every file uploaded from then on lands, and what every delete removes. So the mapping is
 * spelled out id by id, each layout ever written is read back, and what must be refused is listed.
 */
class StorageLayoutTest {

    @ParameterizedTest(name = "id {0} -> {1}")
    @CsvSource({
            "0,       files/s000/0",
            "1,       files/s000/1",
            "123,     files/s000/123",
            "999,     files/s000/999",
            "1000,    files/s001/1000",
            "1234,    files/s001/1234",
            "1999,    files/s001/1999",
            "999999,  files/s999/999999",
            "1000000, files/s1000/1000000",
            "2147483647, files/s2147483/2147483647"
    })
    @DisplayName("a file's directory is files/s{id / 1000, three digits at least}/{id}")
    void shardsByThousands(int fileInfoId, String expected) {
        assertThat(StorageLayout.directoryFor(fileInfoId)).isEqualTo(expected);
    }

    @Test
    @DisplayName("the shard directory is under the reserved top-level name, so no folder can be named across it")
    void livesUnderTheReservedName() {
        assertThat(StorageLayout.directoryFor(42)).startsWith(FolderService.RESERVED_TOP_LEVEL_NAME + "/");
    }

    @Test
    @DisplayName("a negative id is a programming error, not a directory")
    void refusesANegativeId() {
        assertThatThrownBy(() -> StorageLayout.directoryFor(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a thousand consecutive ids fill exactly one shard, and the next id opens the next")
    void aShardHoldsAThousand() {
        for (int id = 5000; id < 6000; id++) {
            assertThat(StorageLayout.directoryFor(id)).startsWith("files/s005/");
        }
        assertThat(StorageLayout.directoryFor(6000)).startsWith("files/s006/");
    }

    /**
     * 1.4.0 stored files flat, {@code files/{id}/...}, and those directories stay. A shard
     * directory must never be spelled like one of them: {@code files/123/} is file 123's whole
     * directory and goes when that file is deleted - with every sharded file inside it, had the
     * shard for ids 123000-123999 been named {@code 123}.
     */
    @Test
    @DisplayName("no shard directory is ever spelled like a 1.4.0 flat id directory")
    void aShardIsNeverABareId() {
        for (int id : new int[]{0, 999, 100_000, 123_456, 999_999, 1_000_000, Integer.MAX_VALUE}) {
            String shard = StorageLayout.directoryFor(id).split("/")[1];
            assertThat(shard).as("shard of %d", id).doesNotMatch("\\d+");
        }
    }

    /**
     * Three layouts share one {@code base-dir}; the whole-file delete needs to know which kind a
     * key is, because under the id-based ones the file's directory is its own and goes with it,
     * and under the name-based one it is shared with every neighbour.
     */
    @Test
    @DisplayName("a key under files/ was written by an id-based layout - 1.4.0's flat one or the sharded one - and a name-based key was not")
    void tellsTheLayoutsApart() {
        assertThat(StorageLayout.isIdBased("files/s000/123/report/v1/report.pdf")).isTrue();
        assertThat(StorageLayout.isIdBased("files/123/report/v1/report.pdf")).as("1.4.0, before the shard").isTrue();
        assertThat(StorageLayout.isIdBased("Finance/Invoices/report/v1/report.pdf")).isFalse();
        assertThat(StorageLayout.isIdBased("filesystem/report/v1/report.pdf")).as("a folder whose name merely begins with it").isFalse();
    }

    // ---------------------------------------------------------------- the key (roadmap 12.5)

    private static final String ID = "6a6d244c-2d97-463a-b7dd-73546b1409ae";

    @Test
    @DisplayName("a new revision's key is files/{shard}/{id}/rev/v{n}/{external id}.{ext}, the extension in lower case")
    void theKey() {
        assertThat(StorageLayout.keyFor(1582, 1, ID, "xlsx")).isEqualTo("files/s001/1582/rev/v1/" + ID + ".xlsx");
        assertThat(StorageLayout.keyFor(1582, 12, ID, "PDF")).isEqualTo("files/s001/1582/rev/v12/" + ID + ".pdf");
        assertThat(StorageLayout.keyFor(7, 1, ID.toUpperCase(), "Docx")).isEqualTo("files/s000/7/rev/v1/" + ID + ".docx");
        assertThat(StorageLayout.keyFor(1_000_000, 3, ID, "tar7")).isEqualTo("files/s1000/1000000/rev/v3/" + ID + ".tar7");
    }

    @Test
    @DisplayName("a key is refused for a version below 1, an external id that is not a UUID, an extension that is not 1-16 letters or digits")
    void theKeyRefusesWhatCannotBe() {
        assertThatThrownBy(() -> StorageLayout.keyFor(1, 0, ID, "pdf")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StorageLayout.keyFor(-1, 1, ID, "pdf")).isInstanceOf(IllegalArgumentException.class);
        for (String notAnId : new String[]{null, "", "report", "../" + ID, ID + "/x", "6a6d244c2d97463ab7dd73546b1409ae"}) {
            assertThatThrownBy(() -> StorageLayout.keyFor(1, 1, notAnId, "pdf")).as("external id %s", notAnId)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String notAnExtension : new String[]{null, "", "p df", "pdf/", "../x", "ٍextفارسی", "a".repeat(17), "tar.gz"}) {
            assertThatThrownBy(() -> StorageLayout.keyFor(1, 1, ID, notAnExtension)).as("extension %s", notAnExtension)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /**
     * Releases up to 2.9.0 read a file's directory as the third parent of a revision's key, and a
     * version's as the first. The new key keeps that depth, so a release rolled back to deletes the
     * file's own directory - never the shard, with a thousand files in it.
     */
    @Test
    @DisplayName("an older release reads the new key right: its third parent is the file's directory, its first the version's")
    void olderReleasesReadTheNewKeyRight() {
        for (int id : new int[]{0, 7, 1582, 999_999, 1_000_000}) {
            String key = StorageLayout.keyFor(id, 4, ID, "pdf");
            assertThat(parent(parent(parent(key)))).as("id %d", id).isEqualTo(StorageLayout.directoryFor(id));
            assertThat(parent(key)).isEqualTo(StorageLayout.directoryFor(id) + "/rev/v4");
        }
    }

    // ---------------------------------------------------------------- a file's directory, read off a key

    @ParameterizedTest(name = "{0} -> {2}")
    @CsvSource(delimiter = '|', value = {
            "files/s001/1582/rev/v1/" + ID + ".xlsx      | 1582 | files/s001/1582",
            "files/s001/1582/(لیست اشخاص) 1404/v2/(لیست اشخاص) 1404.xlsx | 1582 | files/s001/1582",
            "files/s000/12/rev/v1/x.pdf                  | 12   | files/s000/12",
            "files/s1000/1000000/report/v1/report.pdf    | 1000000 | files/s1000/1000000",
            "files/123/report/v1/report.pdf              | 123  | files/123",
            "Finance/Invoices/report/v3/report.pdf       | 77   | Finance/Invoices/report",
            "مالی/فاکتورها/گزارش ماهانه/v1/گزارش ماهانه.pdf | 77 | مالی/فاکتورها/گزارش ماهانه"
    })
    @DisplayName("a file's directory is read off a key of each layout ever written")
    void eachLayoutsDirectory(String key, int fileInfoId, String directory) {
        assertThat(StorageLayout.fileDirectoryOf(key, fileInfoId)).isEqualTo(directory);
        assertThat(StorageLayout.versionDirectoryOf(key, fileInfoId)).isEqualTo(parent(key));
    }

    /**
     * What a delete is about to remove, so nothing is guessed: another file's place, a shard, a key
     * of no known shape, a relative segment - each refused, before any row or byte is touched.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "files/s000/13/rev/v1/x.pdf          | another file's id",
            "files/s001/12/rev/v1/x.pdf          | the wrong shard for the id",
            "files/s000/012/rev/v1/x.pdf         | an id spelled with a leading zero",
            "files/13/report/v1/report.pdf       | another file's flat directory",
            "files/s000/12/v1/x.pdf              | a key one segment short - its third parent the shard",
            "files/s000/12/rev/x.pdf             | no version segment",
            "files/s000/12/rev/v1/extra/x.pdf    | a segment too many",
            "files/s000/99999999999/rev/v1/x.pdf | an id no int holds",
            "files/report/v1/report.pdf          | files/ without an id",
            "Finance/Invoices/v1/report.pdf      | a name-based key one segment short",
            "Finance/../report/v1/report.pdf     | a relative segment",
            "Finance//report/v1/report.pdf       | an empty segment",
            "report.pdf                          | no directory at all"
    })
    @DisplayName("a key that is not this file's, or of no known layout, is refused rather than guessed at")
    void refusesWhatIsNotThisFilesPlace(String key, String why) {
        assertThatThrownBy(() -> StorageLayout.fileDirectoryOf(key, 12)).as(why).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> StorageLayout.versionDirectoryOf(key, 12)).as(why).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a revision with no key is refused, not read as nothing to delete")
    void refusesNoKey() {
        assertThatThrownBy(() -> StorageLayout.fileDirectoryOf(null, 12)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("every key the layout writes is read back to the directory it wrote it under")
    void writtenAndReadAgree() {
        for (int id : new int[]{0, 1, 999, 1000, 1582, 999_999, 1_000_000, Integer.MAX_VALUE}) {
            assertThat(StorageLayout.fileDirectoryOf(StorageLayout.keyFor(id, 1, ID, "pdf"), id))
                    .isEqualTo(StorageLayout.directoryFor(id));
        }
    }

    private static String parent(String key) {
        return key.substring(0, key.lastIndexOf('/'));
    }
}
