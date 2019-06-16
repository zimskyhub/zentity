package com.zmtech.zframework.cache.impl;

import javax.cache.configuration.CompleteConfiguration;
import javax.cache.configuration.MutableConfiguration;

@SuppressWarnings("unused")
public class ZCacheConfiguration<K, V> extends MutableConfiguration<K, V> {
    public ZCacheConfiguration() {
        super();
    }

    public ZCacheConfiguration(CompleteConfiguration<K, V> conf) {
        super(conf);
    }

    int maxEntries = 0;
    long maxCheckSeconds = 30;

    /** Set maximum number of entries in the cache, 0 means no limit (default). Limit is enforced in a scheduled worker, not on put operations. */
    public ZCacheConfiguration<K, V> setMaxEntries(int elements) {
        maxEntries = elements;
        return this;
    }
    public int getMaxEntries() {
        return maxEntries;
    }

    /** Set maximum number of entries in the cache, 0 means no limit (default). */
    public ZCacheConfiguration<K, V> setMaxCheckSeconds(long seconds) {
        maxCheckSeconds = seconds;
        return this;
    }

    public long getMaxCheckSeconds() {
        return maxCheckSeconds;
    }
}
