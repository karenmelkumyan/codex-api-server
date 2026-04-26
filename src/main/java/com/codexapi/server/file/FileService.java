package com.codexapi.server.file;

import com.codexapi.server.http.ApiException;
import com.codexapi.server.session.Session;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class FileService {
    private static final Set<String> SKIPPED_DIRECTORIES = Set.of(
            ".git",
            "target",
            "build",
            "node_modules",
            ".idea",
            ".gradle"
    );
    private static final int BINARY_SAMPLE_BYTES = 8192;

    public Map<String, Object> tree(Session session, String requestedPath, int maxDepth, int limit) {
        if (maxDepth < 1) {
            throw validationError("maxDepth must be positive", "maxDepth");
        }
        if (limit < 1) {
            throw validationError("limit must be positive", "limit");
        }

        String path = defaultPath(requestedPath);
        PathGuard.GuardedPath guardedPath = PathGuard.resolveExisting(session.workingDirectory(), path);
        if (!Files.isDirectory(guardedPath.path(), LinkOption.NOFOLLOW_LINKS)) {
            throw validationError("path must be a directory", "path");
        }

        TreeCollector collector = new TreeCollector(guardedPath.realRoot(), guardedPath.path(), limit);
        try {
            Files.walkFileTree(guardedPath.path(), Set.of(), maxDepth, collector);
        } catch (IOException exception) {
            throw new ApiException(500, "INTERNAL_ERROR", "Unable to read project tree.");
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("sessionId", session.sessionId());
        response.put("root", guardedPath.realRoot().toString());
        response.put("path", path);
        response.put("entries", collector.entries());
        response.put("truncated", collector.truncated());
        return response;
    }

    public Map<String, Object> content(Session session, String requestedPath, int maxBytes, String encoding) {
        if (requestedPath == null || requestedPath.isBlank()) {
            throw validationError("path is required", "path");
        }
        if (maxBytes < 1) {
            throw validationError("maxBytes must be positive", "maxBytes");
        }

        Charset charset = charset(encoding);
        PathGuard.GuardedPath guardedPath = PathGuard.resolveExisting(session.workingDirectory(), requestedPath);
        if (!Files.isRegularFile(guardedPath.path())) {
            throw validationError("path must be a regular file", "path");
        }
        if (isBinaryFile(guardedPath.path())) {
            throw new ApiException(
                    400,
                    "BINARY_FILE_NOT_SUPPORTED",
                    "Binary files are not supported by this endpoint.",
                    Map.of("path", guardedPath.relativePath())
            );
        }

        long sizeBytes;
        byte[] contentBytes;
        try {
            sizeBytes = Files.size(guardedPath.path());
            try (InputStream inputStream = Files.newInputStream(guardedPath.path())) {
                contentBytes = inputStream.readNBytes(maxBytes + 1);
            }
        } catch (IOException exception) {
            throw new ApiException(500, "INTERNAL_ERROR", "Unable to read file content.");
        }

        boolean truncated = contentBytes.length > maxBytes;
        if (truncated) {
            byte[] truncatedBytes = new byte[maxBytes];
            System.arraycopy(contentBytes, 0, truncatedBytes, 0, maxBytes);
            contentBytes = truncatedBytes;
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("sessionId", session.sessionId());
        response.put("path", guardedPath.relativePath());
        response.put("encoding", charset.name());
        response.put("sizeBytes", sizeBytes);
        response.put("truncated", truncated);
        response.put("content", new String(contentBytes, charset));
        return response;
    }

    private static String defaultPath(String requestedPath) {
        return requestedPath == null || requestedPath.isBlank() ? "." : requestedPath.trim();
    }

    private static Charset charset(String encoding) {
        String value = encoding == null || encoding.isBlank() ? StandardCharsets.UTF_8.name() : encoding.trim();
        try {
            return Charset.forName(value);
        } catch (IllegalCharsetNameException | UnsupportedCharsetException exception) {
            throw validationError("encoding is not supported", "encoding");
        }
    }

    private static boolean isBinaryFile(Path path) {
        try (InputStream inputStream = Files.newInputStream(path)) {
            byte[] sample = inputStream.readNBytes(BINARY_SAMPLE_BYTES);
            for (byte value : sample) {
                if (value == 0) {
                    return true;
                }
            }
            return false;
        } catch (IOException exception) {
            throw new ApiException(500, "INTERNAL_ERROR", "Unable to inspect file content.");
        }
    }

    private static ApiException validationError(String message, String field) {
        return new ApiException(400, "VALIDATION_ERROR", message, Map.of("field", field));
    }

    private static final class TreeCollector extends SimpleFileVisitor<Path> {
        private final Path realRoot;
        private final Path startPath;
        private final int limit;
        private final List<Map<String, Object>> entries = new ArrayList<>();
        private boolean truncated;

        private TreeCollector(Path realRoot, Path startPath, int limit) {
            this.realRoot = realRoot;
            this.startPath = startPath;
            this.limit = limit;
        }

        private List<Map<String, Object>> entries() {
            return entries;
        }

        private boolean truncated() {
            return truncated;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
            if (!directory.equals(startPath) && SKIPPED_DIRECTORIES.contains(fileName(directory))) {
                return FileVisitResult.SKIP_SUBTREE;
            }

            if (!directory.equals(startPath)) {
                addEntry(directory, "directory", null);
                if (truncated) {
                    return FileVisitResult.TERMINATE;
                }
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
            if (attributes.isDirectory() && SKIPPED_DIRECTORIES.contains(fileName(file))) {
                return FileVisitResult.CONTINUE;
            }
            if (attributes.isSymbolicLink() && !PathGuard.isExistingPathInsideRoot(realRoot, file)) {
                return FileVisitResult.CONTINUE;
            }

            String type;
            Long sizeBytes = null;
            if (attributes.isSymbolicLink()) {
                type = "symlink";
            } else if (attributes.isDirectory()) {
                type = "directory";
            } else {
                type = "file";
                sizeBytes = attributes.size();
            }
            addEntry(file, type, sizeBytes);
            return truncated ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exception) {
            return FileVisitResult.CONTINUE;
        }

        private void addEntry(Path path, String type, Long sizeBytes) {
            if (entries.size() >= limit) {
                truncated = true;
                return;
            }

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("path", PathGuard.relativePath(realRoot, path));
            entry.put("type", type);
            if (sizeBytes != null) {
                entry.put("sizeBytes", sizeBytes);
            }
            entries.add(entry);

            if (entries.size() >= limit) {
                truncated = true;
            }
        }

        private static String fileName(Path path) {
            Path fileName = path.getFileName();
            return fileName == null ? "" : fileName.toString();
        }
    }
}
