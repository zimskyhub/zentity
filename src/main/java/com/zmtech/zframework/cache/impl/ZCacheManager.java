package com.zmtech.zframework.cache.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.cache.Cache;
import javax.cache.CacheManager;
import javax.cache.configuration.Configuration;
import javax.cache.spi.CachingProvider;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/** This class does not completely support the javax.cache.CacheManager spec, it is just enough to use as a factory for ZCache instances. */
public class ZCacheManager implements CacheManager {
    private static final Logger logger = LoggerFactory.getLogger(ZCacheManager.class);

    private static final ZCacheManager singleCacheManager = new ZCacheManager();
    public static ZCacheManager getMCacheManager() { return singleCacheManager; }

    private URI cmUri = null;
    private ClassLoader localClassLoader;
    private Properties props = new Properties();
    private Map<String, ZCache> cacheMap = new LinkedHashMap<>();
    private boolean isClosed = false;

    private ZCacheManager() {
        try { cmUri = new URI("ZCacheManager"); }
        catch (URISyntaxException e) { logger.error("URI Syntax error initializing ZCacheManager", e); }
        localClassLoader = Thread.currentThread().getContextClassLoader();
    }

    @Override
    public CachingProvider getCachingProvider() { return null; }
    @Override
    public URI getURI() { return cmUri; }
    @Override
    public ClassLoader getClassLoader() { return localClassLoader; }
    @Override
    public Properties getProperties() { return props; }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized <K, V, C extends Configuration<K, V>> Cache<K, V> createCache(String cacheName, C configuration) throws IllegalArgumentException {
        if (isClosed) throw new IllegalStateException("ZCacheManager is closed");
        if (cacheMap.containsKey(cacheName)) {
            // not per spec, but be more friendly and just return the existing cache: throw new CacheException("Cache with name " + cacheName + " already exists");
            return cacheMap.get(cacheName);
        }

        ZCache<K, V> newCache = new ZCache(cacheName, this, configuration);
        cacheMap.put(cacheName, newCache);
        return newCache;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <K, V> Cache<K, V> getCache(String cacheName, Class<K> keyType, Class<V> valueType) {
        if (isClosed) throw new IllegalStateException("ZCacheManager is closed");
        return cacheMap.get(cacheName);
    }
    @Override
    @SuppressWarnings("unchecked")
    public <K, V> Cache<K, V> getCache(String cacheName) {
        if (isClosed) throw new IllegalStateException("ZCacheManager is closed");
        return cacheMap.get(cacheName);
    }

    @Override
    public Iterable<String> getCacheNames() {
        if (isClosed) throw new IllegalStateException("ZCacheManager is closed");
        return cacheMap.keySet();
    }

    @Override
    public void destroyCache(String cacheName) {
        if (isClosed) throw new IllegalStateException("ZCacheManager is closed");
        ZCache cache = cacheMap.get(cacheName);
        if (cache != null) {
            cacheMap.remove(cacheName);
            cache.close();
        } else {
            throw new IllegalStateException("Cache with name " + cacheName + " does not exist, cannot be destroyed");
        }
    }

    @Override
    public void enableManagement(String cacheName, boolean enabled) {
        throw new UnsupportedOperationException("ZCacheManager does not support CacheMXBean"); }
    @Override
    public void enableStatistics(String cacheName, boolean enabled) {
        throw new UnsupportedOperationException("ZCacheManager does not support registered statistics; use the ZCache.getStats() or getMStats() methods"); }

    @Override
    public void close() {
        cacheMap.clear();
        // doesn't work well with current singleton approach: isClosed = true;
    }
    @Override
    public boolean isClosed() { return isClosed; }

    @Override
    public <T> T unwrap(Class<T> clazz) {
        if (clazz.isAssignableFrom(this.getClass())) return clazz.cast(this);
        throw new IllegalArgumentException("Class " + clazz.getName() + " not compatible with ZCacheManager");
    }
}
