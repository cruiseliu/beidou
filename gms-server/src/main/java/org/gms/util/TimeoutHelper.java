package org.gms.util;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import org.gms.net.server.Server;
import org.gms.server.TimerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Thread-safe timer.
public class TimeoutHelper {
    private static final Logger log = LoggerFactory.getLogger(TimerManager.class);

    /** public interface **/

    private boolean active = false;
    private BiConsumer<Integer, Long> listener = null;
    private Map<Integer, ScheduledFuture<?>> futures = new HashMap<>();

    public static TimeoutHelper createAndStart(BiConsumer<Integer, Long> listener) {
        TimeoutHelper ret = new TimeoutHelper();
        ret.setListener(listener);
        ret.start();
        return ret;
    }

    // Register a listener function, whose signature is `callback(int id, long timestamp)`.
    // The parameters are identical to those provided in `schedule(id, timestamp)`,
    // which means `timestamp` may not be the current time.
    public synchronized void setListener(BiConsumer<Integer, Long> listener) {
        this.listener = listener;
    }

    // Start the timer.
    // Suggest to invoke after `setListener(listener)`.
    public synchronized void start() {
        if (!active) {
            active = true;
            service.start(this);
        }
    }

    // Stop the timer.
    // No more callbacks will be called, but started callbacks will not be interrupted.
    public synchronized void stop() {
        if (active) {
            active = false;
            for (var future : futures.values()) {
                future.cancel(false);
            }
            futures.clear();
            service.stop(this);
        }
    }

    public synchronized boolean isActive() {
        return active;
    }

    // Schedule a call.
    // If timestamp <= now(), the call is queued to the executor thread immediately.
    // If `id` is already scheduled, update its timestamp.
    // Suggest to invoke after `start()`.
    public synchronized void schedule(int id, long timestamp) {
        if (!active) {
            log.warn("Scheduling to inactive timer " + id + ":" + timestamp);
            return;
        }
        var old = futures.remove(id);
        if (old != null) {
            old.cancel(false);
        }
        long now = Server.getInstance().getCurrentTime();
        var future = service.schedule(this, id, timestamp, now);
        if (future != null) {
            futures.put(id, future);
        }
    }

    // Schedule a call, or if timestamp <= now(), call it in current thread before this method returns.
    // If `id` is already scheduled, update its timestamp.
    // Suggest to invoke after `start()`.
    public void scheduleOrTrigger(int id, long timestamp) {
        assert active;
        BiConsumer<Integer, Long> triggerListener = null;
        long now = Server.getInstance().getCurrentTime();
        synchronized(this) {
            var old = futures.remove(id);
            if (old != null) {
                old.cancel(false);
            }
            if (now < timestamp) {
                var future = service.schedule(this, id, timestamp, now);
                futures.put(id, future);
            } else {
                triggerListener = listener;
            }
        }
        if (triggerListener != null) {
            triggerListener.accept(id, timestamp);
        }
    }

    // Cancel a scheduled call.
    // If `id` is never scheduled or has already been called, do nothing.
    public synchronized void cancel(int id) {
        var future = futures.remove(id);
        if (future != null) {
            future.cancel(false);
        }
    }

    private void trigger(int id, long timestamp) {
        BiConsumer<Integer, Long> listenerCopy = null;
        synchronized(this) {
            futures.remove(id);
            listenerCopy = listener;
        }
        if (listenerCopy != null) {
            listenerCopy.accept(id, timestamp);
        }
    }

    /** the real executor **/

    private static final int threadPoolSize = 1;

    private static TimeoutHelperService service = new TimeoutHelperService();

    private static class TimeoutHelperService {
        private Set<TimeoutHelper> instances = new HashSet<>();
        private ScheduledThreadPoolExecutor executor = null;

        protected synchronized void start(TimeoutHelper instance) {
            if (executor == null) {
                startExecutor();
            }
            instances.add(instance);
        }

        protected synchronized void stop(TimeoutHelper instance) {
            instances.remove(instance);
            if (instances.isEmpty()) {
                stopExecutor();
            }
        }

        protected ScheduledFuture<?> schedule(TimeoutHelper child, int id, long timestamp, long now) {
            // NOTE:
            // the timestamps use "game time" while the executor expects system time, but this should not be a problem
            // real current time: frameStart + currentDelayInFrame
            // real scheduled time: frameStart + currentDelayInFrame + (timestamp - frameStart) > timestamp
            if (timestamp <= now) {
                // the caller might be holding a lock and expect us to return in constant time,
                // so call the listener in a worker thread
                executor.execute(() -> child.trigger(id, timestamp));
                return null;
            } else {
                return executor.schedule(() -> child.trigger(id, timestamp), timestamp - now, TimeUnit.MILLISECONDS);
            }
        }

        private void startExecutor() {
            executor = new ScheduledThreadPoolExecutor(threadPoolSize);
            // shutdownNow() will interrupt executing tasks
            // so we use this together with shutdown() to stop scheduled tasks but wait for running tasks
            executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
            // prevent intentional memory leak
            executor.setRemoveOnCancelPolicy(true);
        }

        private void stopExecutor() {
            executor.shutdown();
            try {
                executor.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                log.warn("executor failed to shutdown in time: " + exception);
            } finally {
                executor = null;
            }
        }
    }
}
