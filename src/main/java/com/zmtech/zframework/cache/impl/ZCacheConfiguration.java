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

    /**
     * 设置缓存中的最大条目数，0表示无限制（默认）。 限制在计划的工作程序中执行，而不是在执行操作中执行。
     */
    public ZCacheConfiguration<K, V> setMaxEntries(int elements) {
        maxEntries = elements;
        return this;
    }

    public int getMaxEntries() {
        return maxEntries;
    }

    /**
     * 设置缓存中的最大条目数，0表示无限制（默认）。
     */
    public ZCacheConfiguration<K, V> setMaxCheckSeconds(long seconds) {
        maxCheckSeconds = seconds;
        return this;
    }

    public long getMaxCheckSeconds() {
        return maxCheckSeconds;
    }
}
