package com.terila.extractor.core;

// Written by AI
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Exclusive lock for one extraction output directory.
 *
 * <p>Without it, two runs can both create a staging tree and publish it, and whichever commits last
 * silently loses the other run's documents. The lock is an operating-system file lock held from
 * before staging starts until the run ends, so it also excludes a second JVM.
 *
 * <p>The lock file is a sibling of the output and is intentionally kept after release. Deleting a
 * file that another process may have just opened would allow a third process to create and lock a
 * new file at the same path. A sibling {@code .owner} file records the holding PID and start time
 * for diagnostics; it is separate from the lock file so a waiting process can read it, and it is
 * replaced atomically so a conflict never reports a half-written owner. The operating system
 * releases the lock itself when the process exits.
 */
final class OutputLock implements AutoCloseable {

    private static final String PREFIX = ".terila-extractor-lock-";
    private static final String OWNER_SUFFIX = ".owner";
    private static final long POLL_INTERVAL_MILLIS = 100L;

    private final Path outputRoot;
    private final Path lockFile;
    private final Path ownerFile;
    private final FileChannel channel;
    private final FileLock lock;
    private boolean closed;

    private OutputLock(Path outputRoot, Path lockFile, Path ownerFile, FileChannel channel,
                       FileLock lock) {
        this.outputRoot = outputRoot;
        this.lockFile = lockFile;
        this.ownerFile = ownerFile;
        this.channel = channel;
        this.lock = lock;
    }

    /**
     * Acquires the lock for an output directory, creating its parent directory when needed.
     *
     * @param outputDirectory final directory the run will publish into.
     * @return the held lock; close it after the run.
     * @throws OutputLockedException when another run already holds the same lock.
     * @throws IOException           when the lock file cannot be opened or locked.
     */
    static OutputLock acquire(Path outputDirectory) throws IOException {
        return acquire(outputDirectory, Duration.ZERO);
    }

    /**
     * Acquires the lock, waiting up to {@code wait} for another run to release it.
     *
     * @param outputDirectory final directory the run will publish into.
     * @param wait            maximum time to wait; zero fails immediately.
     * @return the held lock; close it after the run.
     * @throws OutputLockedException when the lock is still held when the wait expires.
     * @throws IOException           when the lock file cannot be opened or locked, or the wait is
     *                               interrupted.
     */
    static OutputLock acquire(Path outputDirectory, Duration wait) throws IOException {
        Objects.requireNonNull(wait, "wait");
        if (wait.isNegative()) {
            throw new IllegalArgumentException("wait must not be negative");
        }
        Path outputRoot = Objects.requireNonNull(outputDirectory, "outputDirectory")
                .toAbsolutePath()
                .normalize();
        Path parent = outputRoot.getParent();
        if (parent == null) {
            throw new IOException("Output directory has no parent: " + outputRoot);
        }
        Files.createDirectories(parent);
        Path lockFile = parent.resolve(PREFIX + outputRoot.getFileName());
        Path ownerFile = lockFile.resolveSibling(lockFile.getFileName() + OWNER_SUFFIX);
        long waitNanos = wait.toNanos();
        FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            long deadline = System.nanoTime() + waitNanos;
            while (true) {
                FileLock lock = null;
                try {
                    lock = channel.tryLock();
                } catch (OverlappingFileLockException overlap) {
                    // This JVM already holds it; keep polling until the owner releases it.
                }
                if (lock != null) {
                    writeOwner(ownerFile);
                    return new OutputLock(outputRoot, lockFile, ownerFile, channel, lock);
                }

                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    throw new OutputLockedException(outputRoot, lockFile, readOwner(ownerFile), null);
                }
                try {
                    Thread.sleep(Math.min(POLL_INTERVAL_MILLIS,
                            Math.max(1L, remaining / 1_000_000L)));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for output lock " + lockFile,
                            interrupted);
                }
            }
        } catch (IOException | RuntimeException failure) {
            closeAfterFailure(channel, failure);
            throw failure;
        }
    }

    /** Final directory protected by this lock. */
    Path outputRoot() {
        return outputRoot;
    }

    /** Empty sibling file carrying the operating-system lock. */
    Path lockFile() {
        return lockFile;
    }

    /** Diagnostic file describing the process that currently holds the lock. */
    Path ownerFile() {
        return ownerFile;
    }

    /**
     * Releases the lock. The lock file and owner file stay in place; see the class documentation.
     *
     * @throws IOException when releasing or closing the channel fails.
     */
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;

        IOException failure = null;
        try {
            lock.release();
        } catch (IOException releaseFailure) {
            failure = releaseFailure;
        }
        try {
            channel.close();
        } catch (IOException closeFailure) {
            if (failure == null) {
                failure = closeFailure;
            } else {
                failure.addSuppressed(closeFailure);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static void closeAfterFailure(FileChannel channel, Throwable failure) {
        try {
            channel.close();
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static void writeOwner(Path ownerFile) throws IOException {
        Instant started = ProcessHandle.current().info().startInstant().orElseGet(Instant::now);
        String owner = "pid=" + ProcessHandle.current().pid()
                + "\nstarted=" + started + "\n";
        Path temporary = Files.createTempFile(ownerFile.getParent(),
                ownerFile.getFileName() + "-", ".tmp");
        try {
            Files.writeString(temporary, owner, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, ownerFile, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException | FileAlreadyExistsException notAtomic) {
                Files.move(temporary, ownerFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // A left-over temporary file is harmless; the owner file itself is complete.
            }
        }
    }

    private static String readOwner(Path ownerFile) {
        try {
            String owner = Files.readString(ownerFile, StandardCharsets.UTF_8).strip();
            return owner.isEmpty() ? null : owner.replace("\r", "").replace("\n", "; ");
        } catch (IOException | RuntimeException unavailable) {
            return null;
        }
    }
}

