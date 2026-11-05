package com.terila.extractor.core;

// Written by AI
import java.nio.file.Path;

/** Child-JVM entry point used to verify that the output lock crosses process boundaries. */
public final class LockProbe {

    private LockProbe() {
    }

    public static void main(String[] args) throws Exception {
        try (OutputLock lock = OutputLock.acquire(Path.of(args[0]))) {
            System.out.println("ACQUIRED " + lock.ownerFile());
        } catch (OutputLockedException locked) {
            System.out.println("LOCKED " + locked.getMessage());
        }
    }
}

