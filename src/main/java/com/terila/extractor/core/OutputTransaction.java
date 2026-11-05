package com.terila.extractor.core;

// Written by AI
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A staged extraction output that is published only after the run completed.
 *
 * <p>Writing directly into the final output directory makes a crash destructive: the previous
 * successful run has already been removed, while the new one is incomplete. This class gives the
 * runner a sibling staging directory with the same parent, so all documents and binary assets are
 * written there first. {@link #commit()} then moves the completed managed layout into place, keeping
 * files at the output root that the extractor does not own.
 *
 * <p>The commit itself is rollback-safe for ordinary I/O failures: the previous managed entries are
 * moved to a sibling backup before the staged entries are published, and restored if publication
 * fails. The backup is deleted only after a successful publication. A process kill in the middle of
 * the commit can still leave a mixed tree, but the much more common failure — an exception during
 * extraction, validation or metadata writing — leaves the previous output entirely untouched.
 *
 * <p>Use with try-with-resources. {@link #close()} removes the staging directory unless
 * {@link #commit()} succeeded.
 */
public final class OutputTransaction implements AutoCloseable {

    private static final String STAGING_PREFIX = ".terila-extractor-staging-";
    private static final String BACKUP_PREFIX = ".terila-extractor-backup-";

    private final Path outputRoot;
    private final Path stagingRoot;
    private boolean committed;
    private boolean closed;

    private OutputTransaction(Path outputRoot, Path stagingRoot) {
        this.outputRoot = outputRoot;
        this.stagingRoot = stagingRoot;
    }

    /**
     * Creates an empty staging directory next to the final output.
     *
     * <p>The sibling location is deliberate: a later move has to stay on the same file system to be
     * cheap and atomic. The directory name is unique, so a crashed run does not block the next one.
     *
     * @param outputDirectory final output directory the run should publish to.
     * @return the open transaction; close it or commit it.
     * @throws IOException when the parent directory or the staging directory cannot be created.
     */
    public static OutputTransaction begin(Path outputDirectory) throws IOException {
        Path outputRoot = Objects.requireNonNull(outputDirectory, "outputDirectory")
                .toAbsolutePath()
                .normalize();
        Path parent = outputRoot.getParent();
        if (parent == null) {
            throw new IOException("Output directory has no parent: " + outputRoot);
        }
        Files.createDirectories(parent);
        Path staging = Files.createTempDirectory(parent, STAGING_PREFIX);
        return new OutputTransaction(outputRoot, staging);
    }

    /** Final directory the staged run will be published into. */
    public Path outputRoot() {
        return outputRoot;
    }

    /** Directory the run should write into. */
    public Path stagingRoot() {
        return stagingRoot;
    }

    /**
     * Publishes the staged output.
     *
     * <p>When the output does not exist yet, the staging directory itself becomes the output in one
     * move. When it exists, every top-level entry the staged run produced is moved into place after
     * the corresponding previous entry and every stale managed entry have been moved to a backup.
     * Root entries that neither the old managed layout nor the new run names are left untouched.
     *
     * @throws IOException when the previous output cannot be backed up or the staged output cannot
     *                     be moved into place; the previous managed entries are restored where
     *                     possible.
     * @throws IllegalStateException when called twice or after {@link #close()}.
     */
    public void commit() throws IOException {
        if (committed) {
            throw new IllegalStateException("Output transaction is already committed");
        }
        if (closed) {
            throw new IllegalStateException("Output transaction is already closed");
        }
        if (!Files.isDirectory(stagingRoot)) {
            throw new IOException("Staging directory is missing: " + stagingRoot);
        }
        if (!Files.exists(outputRoot)) {
            move(stagingRoot, outputRoot);
            committed = true;
            return;
        }
        if (!Files.isDirectory(outputRoot)) {
            throw new IOException("Output path is not a directory: " + outputRoot);
        }

        Path backup = Files.createTempDirectory(outputRoot.getParent(), BACKUP_PREFIX);
        List<String> movedOld = new ArrayList<>();
        List<String> movedNew = new ArrayList<>();
        try {
            List<String> staged = listNames(stagingRoot);

            // Managed names are replaced even when this run did not produce them, so a managed
            // directory removed by a new extractor does not survive as stale data. Extra top-level
            // names written by the run are published too, while unrelated old files stay in place.
            Set<String> toReplace = new LinkedHashSet<>(OutputDirectoryManager.managedRootEntries());
            toReplace.addAll(staged);
            for (String name : toReplace) {
                Path previous = outputRoot.resolve(name);
                if (Files.exists(previous, LinkOption.NOFOLLOW_LINKS)) {
                    move(previous, backup.resolve(name));
                    movedOld.add(name);
                }
            }

            for (String name : staged) {
                move(stagingRoot.resolve(name), outputRoot.resolve(name));
                movedNew.add(name);
            }
        } catch (IOException | RuntimeException failure) {
            rollback(failure, movedNew, movedOld, backup);
            throw failure;
        }

        committed = true;
        deleteQuietly(backup);
        deleteQuietly(stagingRoot);
    }

    /**
     * Removes the staging directory when the run did not commit.
     *
     * <p>After a successful {@link #commit()} this is a no-op. A cleanup failure is raised as an
     * unchecked exception; try-with-resources suppresses it when the run already failed with another
     * exception.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        try {
            if (!committed) {
                FileManager.deleteRecursively(stagingRoot);
            }
        } finally {
            closed = true;
        }
    }

    private void rollback(Throwable failure, List<String> movedNew, List<String> movedOld, Path backup) {
        for (int i = movedNew.size() - 1; i >= 0; i--) {
            String name = movedNew.get(i);
            Path published = outputRoot.resolve(name);
            try {
                move(published, stagingRoot.resolve(name));
            } catch (IOException | RuntimeException moveBack) {
                failure.addSuppressed(moveBack);
                try {
                    FileManager.deleteRecursively(published);
                } catch (RuntimeException deleteFailure) {
                    failure.addSuppressed(deleteFailure);
                }
            }
        }

        boolean restored = true;
        for (int i = movedOld.size() - 1; i >= 0; i--) {
            String name = movedOld.get(i);
            try {
                move(backup.resolve(name), outputRoot.resolve(name));
            } catch (IOException | RuntimeException restoreFailure) {
                restored = false;
                failure.addSuppressed(restoreFailure);
            }
        }
        if (restored) {
            deleteQuietly(backup);
        }
    }

    private static List<String> listNames(Path directory) throws IOException {
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            FileManager.deleteRecursively(path);
        } catch (RuntimeException ignored) {
            // A leftover backup or empty staging directory does not invalidate the published output.
        }
    }
}

