package org.lolobored.plexdownloader.transcode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class FileBrowserServiceTest {

    @TempDir
    Path tempDir;

    FileBrowserService service() {
        return new FileBrowserService();
    }

    @Test
    void listDirectories_returnsSubdirectories() throws Exception {
        Files.createDirectory(tempDir.resolve("alpha"));
        Files.createDirectory(tempDir.resolve("beta"));
        Files.writeString(tempDir.resolve("file.txt"), "data");

        List<FileBrowserService.DirEntry> entries = service().listDirectories(tempDir.toString());
        assertThat(entries).hasSize(2);
        assertThat(entries).extracting(FileBrowserService.DirEntry::name)
            .containsExactlyInAnyOrder("alpha", "beta");
    }

    @Test
    void listDirectories_blankPathDefaultsToFilesystemRoot() throws Exception {
        // Blank path → list "/" (filesystem root)
        List<FileBrowserService.DirEntry> entries = service().listDirectories("");
        // On any OS the root always has some entries
        assertThat(entries).isNotNull();
    }

    @Test
    void listDirectories_nullPathDefaultsToFilesystemRoot() throws Exception {
        List<FileBrowserService.DirEntry> entries = service().listDirectories(null);
        assertThat(entries).isNotNull();
    }

    @Test
    void listDirectories_anyReadableAbsolutePathIsAllowed(@TempDir Path secondDir) throws Exception {
        // No sandbox — any readable absolute path is allowed (even outside this test's tempDir)
        FileBrowserService svc = service();
        assertThatNoException().isThrownBy(() -> svc.listDirectories(secondDir.toString()));
    }

    @Test
    void createDirectory_createsNestedDir() throws Exception {
        FileBrowserService svc = service();
        String newDir = tempDir + "/parent/child";
        String result = svc.createDirectory(newDir);
        assertThat(Path.of(result)).isDirectory();
    }

    @Test
    void createDirectory_rejectsAlreadyExistingDirectory() throws Exception {
        FileBrowserService svc = service();
        Files.createDirectory(tempDir.resolve("exists"));
        assertThatThrownBy(() -> svc.createDirectory(tempDir + "/exists"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("already exists");
    }

    @Test
    void isWritable_trueForExistingWritableDir() {
        FileBrowserService svc = service();
        assertThat(svc.isWritable(tempDir.toString())).isTrue();
    }

    @Test
    void isWritable_trueForNonExistingPathInsideWritableParent() {
        FileBrowserService svc = service();
        assertThat(svc.isWritable(tempDir + "/new-folder")).isTrue();
    }

    @Test
    void isWritable_falseForNullPath() {
        FileBrowserService svc = service();
        assertThat(svc.isWritable(null)).isFalse();
    }
}
