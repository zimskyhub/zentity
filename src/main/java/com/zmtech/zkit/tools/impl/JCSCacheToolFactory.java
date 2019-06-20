package com.zmtech.zkit.tools.impl;

import com.zmtech.zkit.context.ExecutionContextFactory;
import com.zmtech.zkit.tools.ToolFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.cache.CacheManager;
import javax.cache.Caching;
import javax.cache.spi.CachingProvider;
import java.net.URISyntaxException;
import java.net.URL;

/**
 * 获取JCS CacheManager的工厂; 对Commons JCS没有编译时依赖，只需添加jar文件即可
 * 当前：org.apache.commons：commons-jcs-jcache：2.0-beta-1
 */

public class JCSCacheToolFactory implements ToolFactory<CacheManager> {
    protected final static Logger logger = LoggerFactory.getLogger(JCSCacheToolFactory.class);
    final static String TOOL_NAME = "JCSCache";

    protected ExecutionContextFactory ecf = null;

    protected CacheManager cacheManager = null;

    /** 默认的空构造函数 */
    public JCSCacheToolFactory() { }

    @Override
    public String getName() { return TOOL_NAME; }

    @Override
    public void init(ExecutionContextFactory ecf) { }
    @Override
    public void preFacadeInit(ExecutionContextFactory ecf) {
        this.ecf = ecf;
        // 总是使用服务器缓存提供程序，客户端总是通过网络接口并且速度很慢
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        CachingProvider providerInternal = Caching.getCachingProvider("org.apache.commons.jcs.jcache.JCSCachingProvider", cl);
        URL cmUrl = cl.getResource("cache.ccf");
        logger.info("JCS缓存信息: JCS 配置地址 ["+cmUrl+"]");
        try {
            cacheManager = providerInternal.getCacheManager(cmUrl.toURI(), cl);
        } catch (URISyntaxException e) {
            logger.info("JCS缓存错误: 初始化 JCS CacheManager 失败");
            e.printStackTrace();
        }
        logger.info("JCS缓存信息: 完成 JCS CacheManager 初始化");
    }

    @Override
    public CacheManager getInstance(Object... parameters) {
        if (cacheManager == null) throw new IllegalStateException("JCS缓存错误: JCSCacheToolFactory 未初始化");
        return cacheManager;
    }

    @Override
    public void destroy() {
        // do nothing?
    }

    public ExecutionContextFactory getEcf() { return ecf; }
}
