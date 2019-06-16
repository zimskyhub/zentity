package com.zmtech.zframework.cache;

import com.zmtech.zframework.cache.impl.ZCache;

import javax.cache.Cache;
import java.util.Set;

/** A facade used for managing and accessing Cache instances. */
public interface CacheFacade {
    void clearAllCaches();
    void clearCachesByPrefix(String prefix);

    /** Get the named Cache, creating one based on configuration and defaults if none exists.
     * Defaults to local cache if no configuration found. */
    Cache getCache(String cacheName);
    /** A type-safe variation on getCache for configured caches.
     * @return*/
    <K, V> Cache getCache(String cacheName, Class<K> keyType, Class<V> valueType);
    /** Get the named local Cache (ZCache instance), creating one based on defaults if none exists.
     * If the cache is configured with type != 'local' this will return an error. */
    ZCache getLocalCache(String cacheName);
    /** Get the named distributed Cache, creating one based on configuration and defaults if none exists.
     * If the cache is configured without type != 'distributed' this will return an error. */
    Cache getDistributedCache(String cacheName);

    /** Register an externally created cache for future gets, inclusion in cache management tools, etc.
     * If a cache with the same name exists the call will be ignored (ie like putIfAbsent). */
    void registerCache(Cache cache);

    Set<String> getCacheNames();
    boolean cacheExists(String cacheName);
}
