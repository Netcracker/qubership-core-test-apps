package com.netcracker.it.spring.watch;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.function.BiConsumer;

@Slf4j
@RequiredArgsConstructor
public abstract class AbstractResourceWatcher<T extends HasMetadata> implements Watcher<T>, AutoCloseable {
    protected final KubernetesClient platformClient;
    protected final String namespace;
    private final BiConsumer<Action, T> eventConsumer;

    private final Object lock = new Object();

    private Watch watch = null;
    private volatile boolean isClosed = false;
    private volatile boolean isRetry = false;

    protected void initializeWatchWithRetry() {
        while (!isClosed) {
            synchronized (lock) {
                try {
                    if (isRetry) { // close previous watch and wait if this is a retry iteration
                        if (watch != null) {
                            watch.close();
                        }
                        Thread.sleep(200);
                    }

                    // initialize watcher
                    watch = initializeWatch();
                } catch (InterruptedException e) {
                    log.error("Sleep before re-opening watcher is interrupted: ", e);
                    this.close();
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Watch client was interrupted", e);
                } catch (Exception e) {
                    log.error("Watcher initialization failed: ", e);
                    isRetry = true;
                    continue; // retry watcher initialization
                }
                return;
            }
        }
    }

    protected abstract Watch initializeWatch();

    private void processEvent(final Action action, final T resource) {
        log.info("Got event {} {} {}", action, resource.getKind(), resource.getMetadata().getName());
        eventConsumer.accept(action, resource);
    }

    @Override
    public void eventReceived(Action action, T resource) {
        log.info("Got {} on {}", action, resource);
        try {
            processEvent(action, resource);
        } catch (Exception e) {
            log.error("Watch event processing failed: ", e);
            isRetry = true;
            initializeWatchWithRetry();
        }
    }

    @Override
    public void onClose(WatcherException cause) {
        log.info("Got watcher close event with cause: ", cause);
        initializeWatchWithRetry();
    }

    @Override
    public void close() {
        synchronized (lock) {
            isClosed = true;
            if (watch != null) watch.close();
        }
    }
}