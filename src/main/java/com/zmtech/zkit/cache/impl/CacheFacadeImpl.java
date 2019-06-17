package com.zmtech.zkit.cache.impl;

import com.zmtech.zkit.cache.CacheFacade;
import com.zmtech.zkit.context.impl.ExecutionContextFactoryImpl;
import com.zmtech.zkit.tools.impl.ZCacheToolFactory;
import com.zmtech.zkit.util.CollectionUtil;
import com.zmtech.zkit.util.MNode;
import com.zmtech.zkit.util.ObjectUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.cache.Cache;
import javax.cache.CacheManager;
import javax.cache.configuration.Configuration;
import javax.cache.configuration.Factory;
import javax.cache.configuration.MutableConfiguration;
import javax.cache.expiry.*;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("unused")
public class CacheFacadeImpl implements CacheFacade {
    protected final static Logger logger = LoggerFactory.getLogger(CacheFacadeImpl.class);

    protected final ExecutionContextFactoryImpl ecfi;

    protected CacheManager localCacheManagerInternal = null;
    protected CacheManager distCacheManagerInternal = null;

    public final ConcurrentMap<String, Cache> localCacheMap = new ConcurrentHashMap<>();

    public CacheFacadeImpl(ExecutionContextFactoryImpl ecfi) {
        this.ecfi = ecfi;

        MNode cacheListNode = ecfi.getConfXmlRoot().first("cache-list");
        String localCacheFactoryName = cacheListNode.attribute("local-factory") != null ? cacheListNode.attribute("local-factory") : ZCacheToolFactory.TOOL_NAME;
        localCacheManagerInternal = ecfi.getTool(localCacheFactoryName, CacheManager.class);
    }

    public CacheManager getDistCacheManager() {
        if (distCacheManagerInternal == null) {
            MNode cacheListNode = ecfi.getConfXmlRoot().first("cache-list");
            String distCacheFactoryName = cacheListNode.attribute("distributed-factory") != null ? cacheListNode.attribute("distributed-factory") : ZCacheToolFactory.TOOL_NAME;
            distCacheManagerInternal = ecfi.getTool(distCacheFactoryName, CacheManager.class);
        }
        return distCacheManagerInternal;
    }

    public void destroy() {
        if (localCacheManagerInternal != null) {
            for (String cacheName : localCacheManagerInternal.getCacheNames())
                localCacheManagerInternal.destroyCache(cacheName);
        }
        localCacheMap.clear();
        if (distCacheManagerInternal != null) {
            for (String cacheName : distCacheManagerInternal.getCacheNames())
                distCacheManagerInternal.destroyCache(cacheName);
        }
    }

    @Override
    public void clearAllCaches() {
        for (Cache cache : localCacheMap.values()) cache.clear();
    }

    @Override
    public void clearCachesByPrefix(String prefix) {
        for (Map.Entry<String, Cache> entry : localCacheMap.entrySet()) {
            String tempName = entry.getKey();
            int separatorIndex = tempName.indexOf("__");
            if (separatorIndex > 0) tempName = tempName.substring(separatorIndex + 2);
            if (!tempName.startsWith(prefix)) continue;
            entry.getValue().clear();
        }
    }

    @Override
    public Cache getCache(String cacheName) {
        return getCacheInternal(cacheName, "local");
    }

    @Override
    public <K, V> Cache<K, V> getCache(String cacheName, Class<K> keyType, Class<V> valueType) {
        return getCacheInternal(cacheName, "local");
    }

    @Override
    public ZCache getLocalCache(String cacheName) {
        return (ZCache) getCacheInternal(cacheName, "local").unwrap(ZCache.class);
    }

    @Override
    public Cache getDistributedCache(String cacheName) {
        return getCacheInternal(cacheName, "distributed");
    }

    public Cache getCacheInternal(String cacheName, String defaultCacheType) {
        Cache theCache = localCacheMap.get(cacheName);
        if (theCache == null) {
            localCacheMap.putIfAbsent(cacheName, initCache(cacheName, defaultCacheType));
            theCache = localCacheMap.get(cacheName);
        }
        return theCache;
    }

    @Override
    public void registerCache(Cache cache) {
        String cacheName = cache.getName();
        localCacheMap.putIfAbsent(cacheName, cache);
    }

    @Override
    public boolean cacheExists(String cacheName) {
        return localCacheMap.containsKey(cacheName);
    }

    @Override
    public Set<String> getCacheNames() {
        return localCacheMap.keySet();
    }

    public List<Map<String, Object>> getAllCachesInfo(String orderByField, String filterRegexp) {
        boolean hasFilterRegexp = filterRegexp != null && filterRegexp.length() > 0;
        List<Map<String, Object>> ci = new LinkedList<>();
        for (String cn : localCacheMap.keySet()) {
            if (hasFilterRegexp && !cn.matches("(?i).*" + filterRegexp + ".*")) continue;
            Cache co = getCache(cn);
            /* TODO: somehow support external cache stats like Hazelcast, through some sort of Moqui interface or maybe the JMX bean?
               NOTE: this isn't all that important because we don't have a good use case for distributed caches
            if (co instanceof ICache) {
                ICache ico = co.unwrap(ICache.class)
                CacheStatistics cs = ico.getLocalCacheStatistics()
                CacheConfig conf = co.getConfiguration(CacheConfig.class)
                EvictionConfig evConf = conf.getEvictionConfig()
                ExpiryPolicy expPol = conf.getExpiryPolicyFactory()?.create()
                Long expireIdle = expPol.expiryForAccess?.durationAmount ?: 0
                Long expireLive = expPol.expiryForCreation?.durationAmount ?: 0
                ci.add([name:co.getName(), expireTimeIdle:expireIdle,
                        expireTimeLive:expireLive, maxElements:evConf.getSize(),
                        evictionStrategy:evConf.getEvictionPolicy().name(), size:ico.size(),
                        getCount:cs.getCacheGets(), putCount:cs.getCachePuts(),
                        hitCount:cs.getCacheHits(), missCountTotal:cs.getCacheMisses(),
                        evictionCount:cs.getCacheEvictions(), removeCount:cs.getCacheRemovals(),
                        expireCount:0] as Map<String, Object>)
            } else
            */
            if (co instanceof ZCache) {
                ZCache mc = (ZCache) co.unwrap(ZCache.class);
                ZStats stats = mc.getMStats();
                Long expireIdle = mc.getAccessDuration() != null ? mc.getAccessDuration().getDurationAmount() : 0;
                Long expireLive = mc.getCreationDuration() != null ? mc.getCreationDuration().getDurationAmount() : 0;
                ci.add(new ConcurrentHashMap<String, Object>() {{
                    put("name", co.getName());
                    put("expireTimeIdle", expireIdle);
                    put("expireTimeLive", expireLive);
                    put("maxElements", mc.getMaxEntries());
                    put("evictionStrategy", "LRU");
                    put("size", mc.size());
                    put("getCount", stats.getCacheGets());
                    put("putCount", stats.getCachePuts());
                    put("hitCount", stats.getCacheHits());
                    put("missCountTotal", stats.getCacheMisses());
                    put("evictionCount", stats.getCacheEvictions());
                    put("removeCount", stats.getCacheRemovals());
                    put("expireCount", stats.getCacheExpires());
                }});
            } else {
                logger.warn("无法获取名称为: [" + cn + "] 类型为: [" + co.getClass().getName() + "] 的详细信息");
            }
        }
        if (orderByField != null && !orderByField.isEmpty())
            CollectionUtil.orderMapList(ci, Collections.singletonList(orderByField));
        return ci;
    }

    protected MNode getCacheNode(String cacheName) {
        MNode cacheListNode = ecfi.getConfXmlRoot().first("cache-list");
        MNode cacheElement = cacheListNode.first((MNode it) -> it.getName().equals("cache") && it.attribute("name").equals(cacheName));
        // nothing found? try starts with, ie allow the cache configuration to be a prefix
        if (cacheElement == null) cacheElement = cacheListNode
                .first((MNode it) -> it.getName().equals("cache") && cacheName.startsWith(it.attribute("name")));
        return cacheElement;
    }

    protected synchronized Cache initCache(String cacheName, String defaultCacheType) {
        if (localCacheMap.containsKey(cacheName)) return localCacheMap.get(cacheName);

        if (defaultCacheType == null) defaultCacheType = "local";

        Cache newCache;
        MNode cacheNode = getCacheNode(cacheName);
        if (cacheNode != null) {
            String keyTypeName = cacheNode.attribute("key-type") != null ? cacheNode.attribute("key-type") : "String";
            String valueTypeName = cacheNode.attribute("value-type") != null ? cacheNode.attribute("value-type") : "Object";
            Class keyType = ObjectUtil.getClass(keyTypeName);
            Class valueType = ObjectUtil.getClass(valueTypeName);

            Factory<ExpiryPolicy> expiryPolicyFactory;
            if (cacheNode.attribute("expire-time-idle") != null && !cacheNode.attribute("expire-time-idle").equals("0")) {
                expiryPolicyFactory = AccessedExpiryPolicy.factoryOf(
                        new Duration(TimeUnit.SECONDS, Long.parseLong(cacheNode.attribute("expire-time-idle"))));
            } else if (cacheNode.attribute("expire-time-live") != null && !cacheNode.attribute("expire-time-live").equals("0")) {
                expiryPolicyFactory = CreatedExpiryPolicy.factoryOf(
                        new Duration(TimeUnit.SECONDS, Long.parseLong(cacheNode.attribute("expire-time-live"))));
            } else {
                expiryPolicyFactory = EternalExpiryPolicy.factoryOf();
            }

            String cacheType = cacheNode.attribute("type") != null ? cacheNode.attribute("type") : defaultCacheType;
            CacheManager cacheManager;
            if ("local".equals(cacheType)) {
                cacheManager = localCacheManagerInternal;
            } else if ("distributed".equals(cacheType)) {
                cacheManager = getDistCacheManager();
            } else {
                throw new IllegalArgumentException("不支持 [" + cacheType + "] 类型的缓存");
            }

            Configuration config;
            if (cacheManager instanceof ZCacheManager) {
                // use ZCache
                ZCacheConfiguration mConf = new ZCacheConfiguration();
                mConf.setTypes(keyType, valueType);
                mConf.setStoreByValue(false).setStatisticsEnabled(true);
                mConf.setExpiryPolicyFactory(expiryPolicyFactory);

                String maxElementsStr = cacheNode.attribute("max-elements");
                if (maxElementsStr != null && !maxElementsStr.equals("0")) {
                    int maxElements = Integer.parseInt(maxElementsStr);
                    mConf.setMaxEntries(maxElements);
                }

                config = mConf;
            /* TODO: somehow support external cache configuration like Hazelcast, through some sort of Moqui interface, maybe pass cacheNode to Cache factory?
               NOTE: this isn't all that important because we don't have a good use case for distributed caches, and they can be configured directly through hazelcast.xml or other Hazelcast conf
            } else if (cacheManager instanceof AbstractHazelcastCacheManager) {
                // use Hazelcast
                CacheConfig cacheConfig = new CacheConfig()
                cacheConfig.setTypes(keyType, valueType)
                cacheConfig.setStoreByValue(true).setStatisticsEnabled(true)
                cacheConfig.setExpiryPolicyFactory(expiryPolicyFactory)

                // from here down the settings are specific to Hazelcast (not supported in javax.cache)
                cacheConfig.setName(fullCacheName)
                cacheConfig.setInMemoryFormat(InMemoryFormat.OBJECT)

                String maxElementsStr = cacheNode.attribute("max-elements")
                if (maxElementsStr && maxElementsStr != "0") {
                    int maxElements = Integer.parseInt(maxElementsStr)
                    EvictionPolicy ep = cacheNode.attribute("eviction-strategy") == "least-recently-used" ? EvictionPolicy.LRU : EvictionPolicy.LFU
                    EvictionConfig evictionConfig = new EvictionConfig(maxElements, EvictionConfig.MaxSizePolicy.ENTRY_COUNT, ep)
                    cacheConfig.setEvictionConfig(evictionConfig)
                }

                config = (Configuration) cacheConfig
            */
            } else {
                logger.info("初始化缓存: [" + cacheName + "] 缓存管理器类型为: [" + cacheManager.getClass().getName() + "] 不支持扩展配置选项, 正在使用 Mutable Configuration");
                MutableConfiguration mutConfig = new MutableConfiguration();
                mutConfig.setTypes(keyType, valueType);
                mutConfig.setStoreByValue("distributed".equals(cacheType)).setStatisticsEnabled(true);
                mutConfig.setExpiryPolicyFactory(expiryPolicyFactory);

                config = mutConfig;
            }

            newCache = cacheManager.createCache(cacheName, config);
        } else {
            CacheManager cacheManager;
            boolean storeByValue;
            if ("local".equals(defaultCacheType)) {
                cacheManager = localCacheManagerInternal;
                storeByValue = false;
            } else if ("distributed".equals(defaultCacheType)) {
                cacheManager = getDistCacheManager();
                storeByValue = true;
            } else {
                throw new IllegalArgumentException("不支持默认缓存类型" + defaultCacheType + ",请修改默认缓存类型: defaultCacheType");
            }

            logger.info("正在创建默认类型为: [" + defaultCacheType + "] 缓存名称为: [" + cacheName + "], storeByValue:=${storeByValue}");
            MutableConfiguration mutConfig = new MutableConfiguration();
            mutConfig.setStoreByValue(storeByValue).setStatisticsEnabled(true);
            // any defaults we want here? better to use underlying defaults and conf file settings only
            newCache = cacheManager.createCache(cacheName, mutConfig);
        }

        // NOTE: put in localCacheMap done in caller (getCache)
        return newCache;
    }

    public List<Map<String, Object>> makeElementInfoList(String cacheName, String orderByField) {
        Cache cache = getCache(cacheName);
        if (cache instanceof ZCache) {
            ZCache mCache = (ZCache) cache.unwrap(ZCache.class);
            List<Map<String, Object>> elementInfoList = new ArrayList<>();
            for (Object ce : mCache.getEntryList()) {
                ZEntry entry = (ZEntry) ((Cache.Entry) ce).unwrap(ZEntry.class);
                Map<String, Object> im = new HashMap<String, Object>() {{
                    put("key", entry.getKey());
                    put("value", entry.getValue());
                    put("hitCount", entry.getAccessCount());
                    put("creationTime", new Timestamp(entry.getCreatedTime()));
                }};
                if (entry.getLastUpdatedTime() > 0) im.put("lastUpdateTime", new Timestamp(entry.getLastUpdatedTime()));
                if (entry.getLastAccessTime() > 0) im.put("lastAccessTime", new Timestamp(entry.getLastAccessTime()));
                elementInfoList.add(im);
            }
            if (orderByField != null && !orderByField.isEmpty())
                CollectionUtil.orderMapList(elementInfoList, Arrays.asList(orderByField));
            return elementInfoList;
        } else {
            return new ArrayList<>();
        }
    }
}
