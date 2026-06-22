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
        return new FileBrowserService(tempDir.toString());
    }

    @Test
    void listDirectories_returnsSubdirectoriesInsideRoot() throws Exception {
        Files.createDirectory(tempDir.resolve("alpha"));
        Files.createDirectory(tempDir.resolve("beta"));
        // a file should not be returned
        Files.writeString(tempDir.resolve("file.txt"), "data");

        List<FileBrowserService.DirEntry> entries = service().listDirectories(null);
        assertThat(entries).hasSize(2);
        assertThat(entries).extracting(FileBrowserService.DirEntry::name)
            .containsExactlyInAnyOrder("alpha", "beta");
    }

    @Test
    void listDirectories_defaultsToAllowedRootWhenPathBlank() throws Exception {
        Files.createDirectory(tempDir.resolve("sub"));
        List<FileBrowserService.DirEntry> entries = service().listDirectories("");
        assertThat(entries).extracting(FileBrowserService.DirEntry::name).contains("sub");
    }

    @Test
    void listDirectories_rejectsPathOutsideRoot() {
        FileBrowserService svc = service();
        assertThatThrownBy(() -> svc.listDirectories("/tmp"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("outside");
    }

    @Test
    void listDirectories_rejectsDotDotTraversal() {
        FileBrowserService svc = service();
        // Try to escape via path traversal
        String traversal = tempDir + "/../../../etc";
        assertThatThrownBy(() -> svc.listDirectories(traversal))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void createDirectory_createsNestedDirInsideRoot() throws Exception {
        FileBrowserService svc = service();
        String newDir = tempDir + "/parent/child";
        String result = svc.createDirectory(newDir);
        assertThat(Path.of(result)).isDirectory();
    }

    @Test
    void createDirectory_rejectsPathOutsideRoot() {
        FileBrowserService svc = service();
        assertThatThrownBy(() -> svc.createDirectory("/tmp/evil"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("outside");
    }

    @Test
    void createDirectory_rejectsDotDotEscape() {
        FileBrowserService svc = service();
        String traversal = tempDir + "/../evil";
        assertThatThrownBy(() -> svc.createDirectory(traversal))
            .isInstanceOf(ResponseStatusException.class);
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
    void isWritableWithinRoot_trueForExistingWritableDir() {
        FileBrowserService svc = service();
        assertThat(svc.isWritableWithinRoot(tempDir.toString())).isTrue();
    }

    @Test
    void isWritableWithinRoot_falseForPathOutsideRoot() {
        FileBrowserService svc = service();
        assertThat(svc.isWritableWithinRoot("/tmp/outside")).isFalse();
    }

    @Test
    void isWritableWithinRoot_trueForNonExistingPathInsideRoot() {
        FileBrowserService svc = service();
        assertThat(svc.isWritableWithinRoot(tempDir + "/new-folder")).isTrue();
    }
}
