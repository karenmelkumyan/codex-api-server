package com.codexapi.server.file;

import com.codexapi.server.http.ApiException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;

public final class PathGuard {
    private PathGuard() {
    }

    public static GuardedPath resolveExisting(String sessionRoot, String requestedPath) {
        String safeRequestedPath = requestedPath == null || requestedPath.isBlank() ? "." : requestedPath.trim();

        try {
            Path realRoot = Path.of(sessionRoot).toRealPath();
            Path inputPath = Path.of(safeRequestedPath);
            Path resolvedPath = inputPath.isAbsolute()
                    ? inputPath.normalize()
                    : realRoot.resolve(inputPath).normalize();

            if (!resolvedPath.startsWith(realRoot)) {
                throw outsideRoot(safeRequestedPath);
            }
            if (!Files.exists(resolvedPath, LinkOption.NOFOLLOW_LINKS)) {
                throw new ApiException(
                        404,
                        "FILE_NOT_FOUND",
                        "Requested path was not found.",
                        Map.of("path", safeRequestedPath)
                );
            }

            Path realRequestedPath = resolvedPath.toRealPath();
            if (!realRequestedPath.startsWith(realRoot)) {
                throw outsideRoot(safeRequestedPath);
            }

            String relativePath = relativePath(realRoot, realRequestedPath);
            return new GuardedPath(realRoot, realRequestedPath, relativePath);
        } catch (InvalidPathException exception) {
            throw new ApiException(
                    400,
                    "VALIDATION_ERROR",
                    "Requested path must be a valid path.",
                    Map.of("path", safeRequestedPath)
            );
        } catch (IOException exception) {
            throw new ApiException(
                    500,
                    "INTERNAL_ERROR",
                    "Unable to resolve requested path.",
                    Map.of("path", safeRequestedPath)
            );
        }
    }

    public static boolean isExistingPathInsideRoot(Path realRoot, Path path) {
        try {
            return path.toRealPath().startsWith(realRoot);
        } catch (IOException exception) {
            return false;
        }
    }

    public static String relativePath(Path realRoot, Path path) {
        Path relativePath = realRoot.relativize(path.toAbsolutePath().normalize());
        String value = relativePath.toString();
        return value.isEmpty() ? "." : value;
    }

    private static ApiException outsideRoot(String requestedPath) {
        return new ApiException(
                400,
                "PATH_OUTSIDE_SESSION_ROOT",
                "Requested path must stay inside the session root.",
                Map.of("path", requestedPath)
        );
    }

    public record GuardedPath(Path realRoot, Path path, String relativePath) {
    }
}
