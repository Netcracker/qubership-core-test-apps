package com.netcracker.cloud.core.configservermigration.consulacl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs make targets of cloud-core-local-dev from a qubership-core-bootstrap checkout, the way a developer installs
 * Cloud Core on a local cluster.
 *
 * <p>The checkout is the directory in the system property {@code core-bootstrap.dir}, by default
 * {@code core-bootstrap-repo} at the root of this repository, where the workflows check it out. make reads the
 * variables every call shares, such as the branches under test, from the environment it inherits.
 */
final class LocalDev {

    private static final Duration TIMEOUT = Duration.ofMinutes(15);

    private LocalDev() {
    }

    /** Runs make and waits for it; the output goes to the test log. */
    static void make(String... targetsAndVariables) {
        awaitSuccess(start(targetsAndVariables));
    }

    /** Starts make without waiting for it, for a scenario that changes the stand while make runs. */
    static Process start(String... targetsAndVariables) {
        Path localDev = Path.of(System.getProperty("core-bootstrap.dir", "../../core-bootstrap-repo"))
                .resolve("cloud-core-local-dev").toAbsolutePath().normalize();
        if (!Files.isDirectory(localDev)) {
            throw new IllegalStateException("no cloud-core-local-dev in " + localDev
                    + ": set -Dcore-bootstrap.dir to a qubership-core-bootstrap checkout");
        }
        List<String> command = new ArrayList<>(List.of("make", "-C", localDev.toString()));
        command.addAll(List.of(targetsAndVariables));
        System.out.println("==== " + String.join(" ", command));
        try {
            return new ProcessBuilder(command).inheritIO().start();
        } catch (IOException e) {
            throw new IllegalStateException("cannot start " + String.join(" ", command), e);
        }
    }

    /** Waits for a make started by {@link #start(String...)} and throws unless it succeeds. */
    static void awaitSuccess(Process make) {
        int exitCode = awaitExit(make);
        if (exitCode != 0) {
            throw new IllegalStateException("make exited with " + exitCode + ", see its output above");
        }
    }

    /**
     * Waits for a make started by {@link #start(String...)} and returns its exit code.
     *
     * @throws IllegalStateException when make runs longer than 15 minutes, which kills it
     */
    static int awaitExit(Process make) {
        try {
            if (!make.waitFor(TIMEOUT.toMinutes(), TimeUnit.MINUTES)) {
                make.destroyForcibly();
                throw new IllegalStateException("make did not finish in " + TIMEOUT.toMinutes() + " minutes");
            }
        } catch (InterruptedException e) {
            make.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("make interrupted", e);
        }
        return make.exitValue();
    }
}
