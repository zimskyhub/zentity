package com.zmtech.zkit.cache.impl;

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

/** 该类不完全支持javax.cache.CacheManager规范，它足以用作ZCache实例的工厂。 */
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
        catch (URISyntaxException e) { logger.error("初始化 ZCacheManager 的 URI 错误", e); }
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
        if (isClosed) throw new IllegalStateException("ZCacheManager 已经关闭!");
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
        if (isClosed) throw new IllegalStateException("ZCacheManager 已经关闭!");
        return cacheMap.get(cacheName);
    }
    @Override
    @SuppressWarnings("unchecked")
    public <K, V> Cache<K, V> getCache(String cacheName) {
        if (isClosed) throw new IllegalStateException("ZCacheManager 已经关闭!");
        return cacheMap.get(cacheName);
    }

    @Override
    public Iterable<String> getCacheNames() {
        if (isClosed) throw new IllegalStateException("ZCacheManager 已经关闭!");
        return cacheMap.keySet();
    }

    @Override
    public void destroyCache(String cacheName) {
        if (isClosed) throw new IllegalStateException("ZCacheManager 已经关闭!");
        ZCache cache = cacheMap.get(cacheName);
        if (cache != null) {
            cacheMap.remove(cacheName);
            cache.close();
        } else {
            throw new IllegalStateException("名称为: [" + cacheName + "] 的缓存不存在,无法销毁!");
        }
    }

    @Override
    public void enableManagement(String cacheName, boolean enabled) {
        throw new UnsupportedOperationException("ZCacheManager 暂时不支持 CacheMXBean!"); }
    @Override
    public void enableStatistics(String cacheName, boolean enabled) {
        throw new UnsupportedOperationException("ZCacheManager 暂时不支持registered statistics; 请使用 ZCache.getStats() 或者 getMStats() 方法!"); }

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
        throw new IllegalArgumentException("类型: [" + clazz.getName() + "] 不是ZCacheManager类型!");
    }
}
