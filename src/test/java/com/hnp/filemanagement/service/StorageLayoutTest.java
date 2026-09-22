package com.hnp.filemanagement.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The sharded layout, pinned as a pure unit test: this is the one place the shape of a new
 * revision's directory is written, and a change here changes where every file uploaded from
 * then on lands - so the mapping is spelled out id by id, including the two boundaries of a
 * shard and the point where the shard outgrows three digits.
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
}
