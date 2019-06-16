package com.zmtech.zframework.cache;

import com.zmtech.zframework.cache.impl.ZCache;

import javax.cache.Cache;
import java.util.Set;

/** 用于管理和访问Cache实例 */
public interface CacheFacade {

    /**
     * 清空全部缓存
     */
    void clearAllCaches();

    /**
     * 按照缓存名称前缀清空缓存
     * @param prefix 缓存名称前缀
     */
    void clearCachesByPrefix(String prefix);

    /**
     * 取Cache，根据配置创建一个Cache，如果不存在则创建默认值。
     * 如果未找到配置，则默认为本地缓存。
     * @param cacheName 缓存名称
     * @return 缓存对象
     */
    Cache getCache(String cacheName);
    /**
     * 取类型安全的Cache，
     * @param cacheName 缓存名称
     * @param keyType 键类型
     * @param valueType 值类型
     * @return 缓存对象
     */
    <K, V> Cache getCache(String cacheName, Class<K> keyType, Class<V> valueType);
    /**
     * 获取指定的本地缓存（ZCache），如果不存在根据默认值创建一个缓存。
     * 如果缓存配置了类型！='local'，则会返回错误。
     * @param cacheName 缓存名称
     * @return 缓存对象
     */
    ZCache getLocalCache(String cacheName);
    /**
     * 获取分布式缓存，根据配置创建一个，如果不存在则创建默认值。
     * 如果缓存配置为没有类型！='distributed'，则会返回错误。
     * @param cacheName 缓存名称
     * @return 缓存对象
     */
    Cache getDistributedCache(String cacheName);

    /**
     * 注册外部创建的缓存，包含在缓存管理工具中。
     * 如果存在具有相同名称的缓存，则将忽略该调用。
     * @param cache 缓存对象
     */
    void registerCache(Cache cache);

    /**
     * 获取全部缓存名称列表
     * @return 缓存名称列表
     */
    Set<String> getCacheNames();

    /**
     * 使用缓存名称验证缓存是否存在
     * @param cacheName 缓存名称
     * @return true:存在 false:不存在
     */
    boolean cacheExists(String cacheName);
}
