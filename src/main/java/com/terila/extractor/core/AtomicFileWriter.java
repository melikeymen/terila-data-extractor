package com.terila.extractor.core;

// Written by AI
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;
import java.util.zip.GZIPOutputStream;

/**
 * Writes a file through a sibling temporary file and an atomic rename.
 *
 * <p>If the process dies mid-write the previous contents survive; readers never observe a truncated
 * document. Optional gzip compression is applied on the way out, matching the {@code gzip} flag of
 * {@link ExtractorConfig}.
 */
public final class AtomicFileWriter {

    private AtomicFileWriter() {
    }

    /**
     * Writes text atomically.
     *
     * @param target  destination file.
     * @param gzip    whether to gzip-compress the payload.
     * @param content consumer that writes the payload into the supplied writer.
     * @throws IOException when writing or the final rename fails; the temporary file is removed.
     */
    public static void write(Path target, boolean gzip, Consumer<Writer> content) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        String suffix = gzip ? ".tmp.gz" : ".tmp";
        Path temp = Files.createTempFile(parent != null ? parent : Path.of("."), ".terila-", suffix);
        try {
            if (gzip) {
                try (Writer writer = new BufferedWriter(new OutputStreamWriter(
                        new GZIPOutputStream(Files.newOutputStream(temp)), StandardCharsets.UTF_8))) {
                    content.accept(writer);
                }
            } else {
                try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                    content.accept(writer);
                }
            }

            moveInto(temp, target);
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(temp);
            throw failure;
        }
    }

    /** Publishes a fully written temporary file, falling back when atomic moves are unsupported. */
    private static void moveInto(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            // Some filesystems refuse ATOMIC_MOVE; a plain replace is still better than a
            // partially written destination.
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Writes raw bytes atomically, without compression.
     *
     * <p>The JSON documents go through {@link #write}, but a copied binary asset (a texture, a
     * sound) is not text and must not be re-encoded; this overload keeps the same
     * write-then-rename guarantee for those.
     *
     * @param target  destination file.
     * @param content exact bytes to store.
     * @throws IOException when writing or the final rename fails; the temporary file is removed.
     */
    public static void writeBytes(Path target, byte[] content) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Path temp = Files.createTempFile(parent != null ? parent : Path.of("."), ".terila-", ".tmp");
        try {
            Files.write(temp, content);
            moveInto(temp, target);
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(temp);
            throw failure;
        }
    }

    /**
     * Convenience overload writing a plain string.
     *
     * @param target  destination file.
     * @param gzip    whether to gzip-compress the payload.
     * @param content the text to store.
     * @throws IOException when writing or the final rename fails.
     */
    public static void writeString(Path target, boolean gzip, String content) throws IOException {
        write(target, gzip, writer -> {
            try {
                writer.write(content);
            } catch (IOException e) {
                throw new UncheckedWriteException(e);
            }
        });
    }

    /** Unwraps {@link IOException} thrown from inside a {@link Consumer} that cannot declare it. */
    public static final class UncheckedWriteException extends RuntimeException {

        /**
         * @param cause the I/O failure raised inside the consumer.
         */
        public UncheckedWriteException(IOException cause) {
            super(cause);
        }
    }
}
