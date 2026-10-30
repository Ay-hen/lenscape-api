package photography.photography.api;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

/**
 * Serialises critical sections per key (per photographer) so two customers cannot book the same slot at the same moment.
 * It protects ONE server instance. For several instances use a Mongo transaction or a unique "slot" document (see README, limitations).
 */
@Component
public class KeyedLock {
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public <T> T with(String key, Supplier<T> action) {
        ReentrantLock l = locks.computeIfAbsent(key, k -> new ReentrantLock());
        l.lock();
        try {
            return action.get();
        } finally {
            l.unlock();
        }
    }
}
