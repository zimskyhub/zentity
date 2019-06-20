package com.zmtech.zkit.cache.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.cache.Cache;
import javax.cache.CacheException;
import javax.cache.CacheManager;
import javax.cache.configuration.CacheEntryListenerConfiguration;
import javax.cache.configuration.CompleteConfiguration;
import javax.cache.configuration.Configuration;
import javax.cache.expiry.Duration;
import javax.cache.expiry.ExpiryPolicy;
import javax.cache.integration.CompletionListener;
import javax.cache.management.CacheStatisticsMXBean;
import javax.cache.processor.EntryProcessor;
import javax.cache.processor.EntryProcessorException;
import javax.cache.processor.EntryProcessorResult;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * javax.cache.Cache接口的简单实现。 基本上是一个包含统计和到期的Map的包装器。
 */
@SuppressWarnings("unused")
public class ZCache<K, V> implements Cache<K, V> {
    private static final Logger logger = LoggerFactory.getLogger(ZCache.class);

    private String name;
    private CacheManager manager;
    private Configuration<K, V> configuration;
    // NOTE: use ConcurrentHashMap for write locks and such even if can't so easily use putIfAbsent/etc
    private ConcurrentHashMap<K, ZEntry<K, V>> entryStore = new ConcurrentHashMap<>();
    // currently for future reference, no runtime type checking
    // private Class<K> keyClass = null;
    // private Class<V> valueClass = null;

    private ZStats stats = new ZStats();
    private boolean statsEnabled = true;

    private Duration accessDuration = null;
    private Duration creationDuration = null;
    private Duration updateDuration = null;
    private final boolean hasExpiry;
    private boolean isClosed = false;

    private EvictRunnable evictRunnable = null;
    private ScheduledFuture<?> evictFuture = null;

    private static class WorkerThreadFactory implements ThreadFactory {
        private final ThreadGroup workerGroup = new ThreadGroup("ZCacheEvict");
        private final AtomicInteger threadNumber = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            return new Thread(workerGroup, r, "ZCacheEvict-" + threadNumber.getAndIncrement());
        }
    }

    private static ScheduledThreadPoolExecutor workerPool = new ScheduledThreadPoolExecutor(1, new WorkerThreadFactory());

    static {
        workerPool.setRemoveOnCancelPolicy(true);
    }

    /**
     * 支持一些配置，但管理器和配置都可以为空。
     */
    public ZCache(String name, CacheManager manager, Configuration<K, V> configuration) {
        this.name = name;
        this.manager = manager;
        this.configuration = configuration;
        if (configuration != null) {
            if (configuration instanceof CompleteConfiguration) {
                CompleteConfiguration<K, V> compConf = (CompleteConfiguration<K, V>) configuration;

                statsEnabled = compConf.isStatisticsEnabled();

                if (compConf.getExpiryPolicyFactory() != null) {
                    ExpiryPolicy ep = compConf.getExpiryPolicyFactory().create();
                    accessDuration = ep.getExpiryForAccess();
                    if (accessDuration != null && accessDuration.isEternal()) accessDuration = null;
                    creationDuration = ep.getExpiryForCreation();
                    if (creationDuration != null && creationDuration.isEternal()) creationDuration = null;
                    updateDuration = ep.getExpiryForUpdate();
                    if (updateDuration != null && updateDuration.isEternal()) updateDuration = null;
                }
            }

            // keyClass = configuration.getKeyType();
            // valueClass = configuration.getValueType();
            // TODO: support any other configuration?

            if (configuration instanceof ZCacheConfiguration) {
                ZCacheConfiguration<K, V> zCacheConf = (ZCacheConfiguration<K, V>) configuration;

                if (zCacheConf.maxEntries > 0) {
                    evictRunnable = new EvictRunnable(this, zCacheConf.maxEntries);
                    evictFuture = workerPool.scheduleWithFixedDelay(evictRunnable, 30, zCacheConf.maxCheckSeconds, TimeUnit.SECONDS);
                }
            }
        }
        hasExpiry = accessDuration != null || creationDuration != null || updateDuration != null;
    }

    public synchronized void setMaxEntries(int elements) {
        if (elements == 0) {
            if (evictRunnable != null) {
                evictRunnable = null;
                evictFuture.cancel(false);
                evictFuture = null;
            }
        } else {
            if (evictRunnable != null) {
                evictRunnable.maxEntries = elements;
            } else {
                evictRunnable = new EvictRunnable(this, elements);
                long maxCheckSeconds = 30;
                if (configuration instanceof ZCacheConfiguration)
                    maxCheckSeconds = ((ZCacheConfiguration) configuration).maxCheckSeconds;
                evictFuture = workerPool.scheduleWithFixedDelay(evictRunnable, 1, maxCheckSeconds, TimeUnit.SECONDS);
            }
        }
    }

    public int getMaxEntries() {
        return evictRunnable != null ? evictRunnable.maxEntries : 0;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public V get(K key) {
        ZEntry<K, V> entry = getEntryInternal(key, null, null, 0);
        if (entry == null) return null;
        return entry.value;
    }

    public V get(K key, ExpiryPolicy policy) {
        ZEntry<K, V> entry = getEntryInternal(key, policy, null, 0);
        if (entry == null) return null;
        return entry.value;
    }

    /**
     * 如果条目的最后更新时间在expireBeforeTime之前，则到期。
     * 在知道资源的上次更新时间以查看缓存条目是否过期时非常有用。
     */
    public V get(K key, long expireBeforeTime) {
        ZEntry<K, V> entry = getEntryInternal(key, null, expireBeforeTime, 0);
        if (entry == null) return null;
        return entry.value;
    }

    /**
     * 获取，如果它在缓存中并且没有过期，否则返回null。 使用缓存策略时，策略可以为null。
     */
    public ZEntry<K, V> getEntry(final K key, final ExpiryPolicy policy) {
        return getEntryInternal(key, policy, null, 0);
    }

    /**
     * 获取，不检查是否过期。
     */
    public ZEntry<K, V> getEntryNoCheck(K key) {
        if (isClosed) throw new IllegalStateException("ZCache缓存错误: [" + name + "] 已经关闭!");
        if (key == null) throw new IllegalArgumentException("ZCache缓存错误: 缓存键不能为空!");
        ZEntry<K, V> entry = entryStore.get(key);
        if (entry != null) {
            if (statsEnabled) {
                stats.gets++;
                stats.hits++;
            }
            long accessTime = System.currentTimeMillis();
            entry.accessCount++;
            if (accessTime > entry.lastAccessTime) entry.lastAccessTime = accessTime;
        } else {
            if (statsEnabled) {
                stats.gets++;
                stats.misses++;
            }
        }
        return entry;
    }

    private ZEntry<K, V> getEntryInternal(final K key, final ExpiryPolicy policy, final Long expireBeforeTime, long currentTime) {
        if (isClosed) throw new IllegalStateException("ZCache缓存错误: [" + name + "] 已经关闭!");
        if (key == null) throw new IllegalArgumentException("ZCache缓存错误: 缓存不能为空!");
        ZEntry<K, V> entry = entryStore.get(key);

        if (entry != null) {
            if (policy != null) {
                if (currentTime == 0) currentTime = System.currentTimeMillis();
                if (entry.isExpired(currentTime, policy)) {
                    entryStore.remove(key);
                    entry = null;
                    if (statsEnabled) stats.countExpire();
                }
            } else if (hasExpiry) {
                if (currentTime == 0) currentTime = System.currentTimeMillis();
                if (entry.isExpired(currentTime, accessDuration, creationDuration, updateDuration)) {
                    entryStore.remove(key);
                    entry = null;
                    if (statsEnabled) stats.countExpire();
                }
            }

            if (expireBeforeTime != null && entry != null && entry.lastUpdatedTime < expireBeforeTime) {
                entryStore.remove(key);
                entry = null;
                if (statsEnabled) stats.countExpire();
            }

            if (entry != null) {
                if (statsEnabled) {
                    stats.gets++;
                    stats.hits++;
                }
                entry.accessCount++;
                // 此时如果使用ad-hoc策略或hasExpiry == true 将设置currentTime，
                // 否则将为0意味着我们不需要跟踪lastAccessTime 我们只需要System.currentTimeMillis（））
                // if（currentTime == 0）currentTime = System.currentTimeMillis（）;
                if (currentTime > entry.lastAccessTime) entry.lastAccessTime = currentTime;
            } else {
                if (statsEnabled) {
                    stats.gets++;
                    stats.misses++;
                }
            }
        } else {
            if (statsEnabled) {
                stats.gets++;
                stats.misses++;
            }
        }

        return entry;
    }

    private ZEntry<K, V> getCheckExpired(K key) {
        if (isClosed) throw new IllegalStateException("ZCache缓存错误: [" + name + "] 已经关闭!");
        if (key == null) throw new IllegalArgumentException("ZCache缓存错误: Cache key cannot be null");
        ZEntry<K, V> entry = entryStore.get(key);
        if (hasExpiry && entry != null && entry.isExpired(accessDuration, creationDuration, updateDuration)) {
            entryStore.remove(key);
            entry = null;
            if (statsEnabled) stats.countExpire();
        }
        return entry;
    }

    private ZEntry<K, V> getCheckExpired(K key, long currentTime) {
        if (isClosed) throw new IllegalStateException("ZCache缓存错误: [" + name + "] 已经关闭!");
        if (key == null) throw new IllegalArgumentException("ZCache缓存错误: 缓存键不能为空!");
        ZEntry<K, V> entry = entryStore.get(key);
        if (hasExpiry && entry != null && entry.isExpired(currentTime, accessDuration, creationDuration, updateDuration)) {
            entryStore.remove(key);
            entry = null;
            if (statsEnabled) stats.countExpire();
        }
        return entry;
    }

    @Override
    public Map<K, V> getAll(Set<? extends K> keys) {
        long currentTime = System.currentTimeMillis();
        Map<K, V> results = new HashMap<>();
        for (K key : keys) {
            ZEntry<K, V> entry = getEntryInternal(key, null, null, currentTime);
            results.put(key, entry != null ? entry.value : null);
        }
        return results;
    }

    @Override
    public boolean containsKey(K key) {
        ZEntry<K, V> entry = getCheckExpired(key);
        return entry != null;
    }

    @Override
    public void put(K key, V value) {
        long currentTime = System.currentTimeMillis();
        // get entry, count hit/miss
        ZEntry<K, V> entry = getCheckExpired(key, currentTime);
        if (entry != null) {
            entry.setValue(value, currentTime);
            if (statsEnabled) stats.puts++;
        } else {
            entry = new ZEntry<>(key, value, currentTime);
            entryStore.put(key, entry);
            if (statsEnabled) stats.puts++;
        }
    }

    @Override
    public V getAndPut(K key, V value) {
        long currentTime = System.currentTimeMillis();
        // get entry, count hit/miss
        ZEntry<K, V> entry = getCheckExpired(key, currentTime);
        if (entry != null) {
            V oldValue = entry.value;
            entry.setValue(value, currentTime);
            if (statsEnabled) stats.puts++;
            return oldValue;
        } else {
            entry = new ZEntry<>(key, value, currentTime);
            entryStore.put(key, entry);
            if (statsEnabled) stats.puts++;
            return null;
        }
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> map) {
        if (map == null) return;
        for (Map.Entry<? extends K, ? extends V> me : map.entrySet()) getAndPut(me.getKey(), me.getValue());
    }

    @Override
    public boolean putIfAbsent(K key, V value) {
        long currentTime = System.currentTimeMillis();
        ZEntry<K, V> entry = getCheckExpired(key, currentTime);
        if (entry != null) {
            return false;
        } else {
            entry = new ZEntry<>(key, value, currentTime);
            ZEntry<K, V> existingValue = entryStore.putIfAbsent(key, entry);
            if (existingValue == null) {
                if (statsEnabled) stats.puts++;
                return true;
            } else {
                return false;
            }
        }
    }

    @Override
    public boolean remove(K key) {
        ZEntry<K, V> entry = getCheckExpired(key);
        if (entry != null) {
            entryStore.remove(key);
            if (statsEnabled) stats.countRemoval();
            return true;
        } else {
            return false;
        }
    }

    @Override
    public boolean remove(K key, V oldValue) {
        ZEntry<K, V> entry = getCheckExpired(key);

        if (entry != null) {
            boolean remove = entry.valueEquals(oldValue);
            if (remove) {
                // remove with dummy ZEntry instance for comparison to ensure still equals
                remove = entryStore.remove(key, new ZEntry<>(key, oldValue));
                if (remove && statsEnabled) stats.countRemoval();
            }
            return remove;
        } else {
            return false;
        }
    }

    @Override
    public V getAndRemove(K key) {
        // get entry, count hit/miss
        ZEntry<K, V> entry = getEntryInternal(key, null, null, 0);
        if (entry != null) {
            V oldValue = entry.value;
            entryStore.remove(key);
            if (statsEnabled) stats.countRemoval();
            return oldValue;
        }
        return null;
    }

    @Override
    public boolean replace(K key, V oldValue, V newValue) {
        long currentTime = System.currentTimeMillis();
        ZEntry<K, V> entry = getCheckExpired(key, currentTime);

        if (entry != null) {
            boolean replaced = entry.setValueIfEquals(oldValue, newValue, currentTime);
            if (replaced) if (statsEnabled) stats.puts++;
            return replaced;
        } else {
            return false;
        }
    }

    @Override
    public boolean replace(K key, V value) {
        long currentTime = System.currentTimeMillis();
        ZEntry<K, V> entry = getCheckExpired(key, currentTime);

        if (entry != null) {
            entry.setValue(value, currentTime);
            if (statsEnabled) stats.puts++;
            return true;
        } else {
            return false;
        }
    }

    @Override
    public V getAndReplace(K key, V value) {
        long currentTime = System.currentTimeMillis();
        // get entry, count hit/miss
        ZEntry<K, V> entry = getEntryInternal(key, null, null, currentTime);
        if (entry != null) {
            V oldValue = entry.value;
            entry.setValue(value, currentTime);
            if (statsEnabled) stats.puts++;
            return oldValue;
        } else {
            return null;
        }
    }

    @Override
    public void removeAll(Set<? extends K> keys) {
        if (isClosed) throw new IllegalStateException("ZCache缓存错误: [" + name + "] 已经关闭!");
        for (K key : keys) remove(key);
    }

    @Override
    public void removeAll() {
        if (isClosed) throw new IllegalStateException("ZCache缓存错误: [" + name + "] 已经关闭!");
        int size = entryStore.size();
        entryStore.clear();
        if (statsEnabled) stats.countBulkRemoval(size);
    }

    @Override
    public void clear() {
        if (isClosed) throw new IllegalStateException("ZCache缓存错误: [" + name + "] 已经关闭!");
        // don't track removals or do anything else, removeAll does that
        entryStore.clear();
    }

    @Override
    public <C extends Configuration<K, V>> C getConfiguration(Class<C> clazz) {
        if (configuration == null) return null;
        if (clazz.isAssignableFrom(configuration.getClass())) return clazz.cast(configuration);
        throw new IllegalArgumentException("ZCache缓存错误: 类型 [" + clazz.getName() + "] 不是 [" + configuration.getClass().getName() +" ]类型!");
    }

    @Override
    public void loadAll(Set<? extends K> keys, boolean replaceExistingValues, CompletionListener completionListener) {
        throw new CacheException("ZCache缓存错误: ZCache暂时不支持 loadAll!");
    }

    @Override
    public <T> T invoke(K key, EntryProcessor<K, V, T> entryProcessor, Object... arguments) throws EntryProcessorException {
        throw new CacheException("ZCache缓存错误: ZCache暂时不支持 invoke!");
    }

    @Override
    public <T> Map<K, EntryProcessorResult<T>> invokeAll(Set<? extends K> keys, EntryProcessor<K, V, T> entryProcessor, Object... arguments) {
        throw new CacheException("ZCache缓存错误: ZCache暂时不支持 invokeAll!");
    }

    @Override
    public void registerCacheEntryListener(CacheEntryListenerConfiguration<K, V> cacheEntryListenerConfiguration) {
        throw new CacheException("ZCache缓存错误: ZCache暂时不支持 registerCacheEntryListener!");
    }

    @Override
    public void deregisterCacheEntryListener(CacheEntryListenerConfiguration<K, V> cacheEntryListenerConfiguration) {
        throw new CacheException("ZCache缓存错误: ZCache暂时不支持 deregisterCacheEntryListener!");
    }

    @Override
    public CacheManager getCacheManager() {
        return manager;
    }

    @Override
    public void close() {
        if (isClosed) throw new IllegalStateException("ZCache缓存错误: [" + name + "] 已经成功关闭!");
        isClosed = true;
        entryStore.clear();
    }

    @Override
    public boolean isClosed() {
        return isClosed;
    }

    @Override
    public <T> T unwrap(Class<T> clazz) {
        if (clazz.isAssignableFrom(this.getClass())) return clazz.cast(this);
        throw new IllegalArgumentException("ZCache缓存错误: 类型 [" + clazz.getName() + "] 不是ZCache类型!");
    }

    @Override
    public Iterator<Entry<K, V>> iterator() {
        if (isClosed) throw new IllegalStateException("ZCache缓存错误: 缓存 [" + name + "] 已经关闭!");
        return new CacheIterator<>(this);
    }

    private static class CacheIterator<K, V> implements Iterator<Entry<K, V>> {
        final ZCache<K, V> zCache;
        final long initialTime;
        final ArrayList<ZEntry<K, V>> entryList;
        final int maxIndex;
        int curIndex = -1;
        ZEntry<K, V> curEntry = null;

        CacheIterator(ZCache<K, V> zCache) {
            this.zCache = zCache;
            entryList = new ArrayList<>(zCache.entryStore.values());
            maxIndex = entryList.size() - 1;
            initialTime = System.currentTimeMillis();
        }

        @Override
        public boolean hasNext() {
            return curIndex < maxIndex;
        }

        @Override
        public Entry<K, V> next() {
            curEntry = null;
            while (curIndex < maxIndex) {
                curIndex++;
                curEntry = entryList.get(curIndex);
                if (curEntry.isExpired) {
                    curEntry = null;
                } else if (zCache.hasExpiry && curEntry.isExpired(initialTime, zCache.accessDuration, zCache.creationDuration, zCache.updateDuration)) {
                    zCache.entryStore.remove(curEntry.getKey());
                    if (zCache.statsEnabled) zCache.stats.countExpire();
                    curEntry = null;
                } else {
                    if (zCache.statsEnabled) {
                        zCache.stats.gets++;
                        zCache.stats.hits++;
                    }
                    break;
                }
            }
            return curEntry;
        }

        @Override
        public void remove() {
            if (curEntry != null) {
                zCache.entryStore.remove(curEntry.getKey());
                if (zCache.statsEnabled) zCache.stats.countRemoval();
                curEntry = null;
            }
        }
    }

    /**
     * 获取所有，检查到期时间并计算每个条目的获取
     */
    public ArrayList<Entry<K, V>> getEntryList() {
        if (isClosed) throw new IllegalStateException("ZCache缓存错误: 缓存 [" + name + "] 已经关闭!");
        long currentTime = System.currentTimeMillis();
        ArrayList<K> keyList = new ArrayList<>(entryStore.keySet());
        int keyListSize = keyList.size();
        ArrayList<Entry<K, V>> entryList = new ArrayList<>(keyListSize);
        for (int i = 0; i < keyListSize; i++) {
            K key = keyList.get(i);
            ZEntry<K, V> entry = getCheckExpired(key, currentTime);
            if (entry != null) {
                entryList.add(entry);
                if (statsEnabled) {
                    stats.gets++;
                    stats.hits++;
                }
                entry.accessCount++;
                if (currentTime > entry.lastAccessTime) entry.lastAccessTime = currentTime;
            }
        }
        return entryList;
    }

    /**
     * 清空到期缓存
     */
    public int clearExpired() {
        if (isClosed) throw new IllegalStateException("ZCache缓存错误: 缓存 [" + name + "] 已经关闭!");
        if (!hasExpiry) return 0;
        long currentTime = System.currentTimeMillis();
        ArrayList<K> keyList = new ArrayList<>(entryStore.keySet());
        int keyListSize = keyList.size();
        int expireCount = 0;
        for (int i = 0; i < keyListSize; i++) {
            K key = keyList.get(i);
            ZEntry<K, V> entry = entryStore.get(key);
            if (entry != null && entry.isExpired(currentTime, accessDuration, creationDuration, updateDuration)) {
                entryStore.remove(key);
                if (statsEnabled) stats.countExpire();
                expireCount++;
            }
        }
        return expireCount;
    }

    public CacheStatisticsMXBean getStats() {
        return stats;
    }

    public ZStats getMStats() {
        return stats;
    }

    public int size() {
        return entryStore.size();
    }

    public Duration getAccessDuration() {
        return accessDuration;
    }

    public Duration getCreationDuration() {
        return creationDuration;
    }

    public Duration getUpdateDuration() {
        return updateDuration;
    }

    private static class EvictRunnable<K, V> implements Runnable {
        private static AccessComparator comparator = new AccessComparator();
        private ZCache cache;
        private int maxEntries;

        private EvictRunnable(ZCache mc, int entries) {
            cache = mc;
            maxEntries = entries;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void run() {
            if (maxEntries == 0) return;
            int entriesToEvict = cache.entryStore.size() - maxEntries;
            if (entriesToEvict <= 0) return;

            long startTime = System.currentTimeMillis();

            Collection<ZEntry> entrySet = (Collection<ZEntry>) cache.entryStore.values();
            PriorityQueue<ZEntry> priorityQueue = new PriorityQueue<>(entrySet.size(), comparator);
            priorityQueue.addAll(entrySet);

            int entriesEvicted = 0;
            while (entriesToEvict > 0 && priorityQueue.size() > 0) {
                ZEntry curEntry = priorityQueue.poll();
                // if an entry was expired after pulling the initial value set
                if (curEntry.isExpired) continue;
                cache.entryStore.remove(curEntry.getKey());
                cache.stats.evictions++;
                entriesEvicted++;
                entriesToEvict--;
            }
            long timeElapsed = System.currentTimeMillis() - startTime;
            logger.info("ZCache缓存信息: 从缓存 [" + cache.name + "] 中删除了 [" + entriesEvicted + "] 用时: [" + timeElapsed + "] 毫秒");
        }
    }

    private static class AccessComparator implements Comparator<ZEntry> {
        @Override
        public int compare(ZEntry e1, ZEntry e2) {
            if (e1.accessCount == e2.accessCount) {
                if (e1.lastAccessTime == e2.lastAccessTime) return 0;
                else return e1.lastAccessTime > e2.lastAccessTime ? 1 : -1;
            } else {
                return e1.accessCount > e2.accessCount ? 1 : -1;
            }
        }
    }

}
