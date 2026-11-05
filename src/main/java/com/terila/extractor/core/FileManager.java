package com.terila.extractor.core;

// Written by AI
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Small, dependency-free helpers around {@link Files}. */
public final class FileManager {

    private FileManager() {
    }

    /** Creates a directory and every missing parent. */
    public static Path ensureDirectory(Path directory) {
        try {
            return Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create directory " + directory, e);
        }
    }

    /** Deletes a file or a whole tree; missing paths are ignored. */
    public static void deleteRecursively(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete " + path, e);
        }
    }

    /** Lists the regular files below {@code root}, sorted for deterministic output. */
    public static List<Path> listFiles(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile).sorted(Comparator.naturalOrder()).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list " + root, e);
        }
    }

    /** Recursive byte size of a directory tree, {@code 0} when it does not exist. */
    public static long sizeOf(Path root) {
        return listFiles(root).stream().mapToLong(path -> {
            try {
                return Files.size(path);
            } catch (IOException e) {
                return 0L;
            }
        }).sum();
    }

    /** Whether a directory is missing or contains no entries. */
    public static boolean isEmptyDirectory(Path root) {
        if (!Files.isDirectory(root)) {
            return true;
        }
        try (Stream<Path> stream = Files.list(root)) {
            return stream.findAny().isEmpty();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to inspect " + root, e);
        }
    }
}
