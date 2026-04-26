package com.codexapi.server.file;

import com.codexapi.server.http.ApiException;
import com.codexapi.server.session.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

final class FileServiceTest {
    @TempDir
    Path tempDir;

    private final FileService fileService = new FileService();

    @Test
    void readsNormalTextFileInsideRoot() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("root"));
        Files.writeString(root.resolve("hello.txt"), "hello");

        Map<String, Object> response = fileService.content(session(root), "hello.txt", 200_000, "UTF-8");

        assertEquals("hello.txt", response.get("path"));
        assertEquals("hello", response.get("content"));
        assertEquals(false, response.get("truncated"));
    }

    @Test
    void rejectsTraversalOutsideRoot() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("root"));

        ApiException exception = assertThrows(
                ApiException.class,
                () -> fileService.content(session(root), "../outside.txt", 200_000, "UTF-8")
        );

        assertEquals("PATH_OUTSIDE_SESSION_ROOT", exception.code());
    }

    @Test
    void rejectsSymlinkFilePointingOutsideRoot() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("root"));
        Path outside = Files.writeString(tempDir.resolve("outside.txt"), "outside");
        Path link = root.resolve("link.txt");
        assumeSymlinkCreated(link, outside);

        ApiException exception = assertThrows(
                ApiException.class,
                () -> fileService.content(session(root), "link.txt", 200_000, "UTF-8")
        );

        assertEquals("PATH_OUTSIDE_SESSION_ROOT", exception.code());
    }

    @Test
    void treeDoesNotFollowSymlinkDirectoryOutsideRoot() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("root"));
        Path outsideDir = Files.createDirectories(tempDir.resolve("outside"));
        Files.writeString(outsideDir.resolve("secret.txt"), "secret");
        Path link = root.resolve("external");
        assumeSymlinkCreated(link, outsideDir);

        Map<String, Object> response = fileService.tree(session(root), ".", 4, 100);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) response.get("entries");
        assertFalse(entries.stream().anyMatch(entry -> "external/secret.txt".equals(entry.get("path"))));
    }

    @Test
    void missingFileReturnsFileNotFound() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("root"));

        ApiException exception = assertThrows(
                ApiException.class,
                () -> fileService.content(session(root), "missing.txt", 200_000, "UTF-8")
        );

        assertEquals("FILE_NOT_FOUND", exception.code());
    }

    @Test
    void directoryPathIsRejectedForContent() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("root"));
        Files.createDirectories(root.resolve("dir"));

        ApiException exception = assertThrows(
                ApiException.class,
                () -> fileService.content(session(root), "dir", 200_000, "UTF-8")
        );

        assertEquals("VALIDATION_ERROR", exception.code());
    }

    @Test
    void maxBytesTruncatesOutput() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("root"));
        Files.writeString(root.resolve("long.txt"), "abcdef");

        Map<String, Object> response = fileService.content(session(root), "long.txt", 3, "UTF-8");

        assertEquals("abc", response.get("content"));
        assertEquals(true, response.get("truncated"));
    }

    @Test
    void obviousBinaryFileIsRejected() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("root"));
        Files.write(root.resolve("binary.bin"), new byte[] { 65, 0, 66 });

        ApiException exception = assertThrows(
                ApiException.class,
                () -> fileService.content(session(root), "binary.bin", 200_000, "UTF-8")
        );

        assertEquals("BINARY_FILE_NOT_SUPPORTED", exception.code());
    }

    private void assumeSymlinkCreated(Path link, Path target) throws Exception {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | FileSystemException | SecurityException exception) {
            assumeTrue(false, "Symlink creation is not supported in this environment");
        }
    }

    private Session session(Path root) {
        return new Session(
                "s_test",
                "test",
                root.toAbsolutePath().normalize().toString(),
                "",
                "2026-01-01T00:00:00Z",
                null,
                600
        );
    }
}
