package com.zmtech.zframework.tools.impl;

import com.zmtech.zframework.context.ExecutionContextFactory;
import com.zmtech.zframework.tools.ToolFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.cache.CacheManager;
import javax.cache.Caching;
import javax.cache.spi.CachingProvider;
import java.net.URISyntaxException;
import java.net.URL;

/** A factory for getting a JCS CacheManager; this has no compile time dependency on Commons JCS, just add the jar files
 * Current artifact: org.apache.commons:commons-jcs-jcache:2.0-beta-1
 */

public class JCSCacheToolFactory implements ToolFactory<CacheManager> {
    protected final static Logger logger = LoggerFactory.getLogger(JCSCacheToolFactory.class);
    final static String TOOL_NAME = "JCSCache";

    protected ExecutionContextFactory ecf = null;

    protected CacheManager cacheManager = null;

    /** Default empty constructor */
    public JCSCacheToolFactory() { }

    @Override
    public String getName() { return TOOL_NAME; }

    @Override
    public void init(ExecutionContextFactory ecf) { }
    @Override
    public void preFacadeInit(ExecutionContextFactory ecf) {
        this.ecf = ecf;
        // always use the server caching provider, the client one always goes over a network interface and is slow
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        CachingProvider providerInternal = Caching.getCachingProvider("org.apache.commons.jcs.jcache.JCSCachingProvider", cl);
        URL cmUrl = cl.getResource("cache.ccf");
        logger.info("JCS config URI: ${cmUrl}");
        try {
            cacheManager = providerInternal.getCacheManager(cmUrl.toURI(), cl);
        } catch (URISyntaxException e) {
            logger.info("Initialized JCS CacheManager failed");
            e.printStackTrace();
        }
        logger.info("Initialized JCS CacheManager");
    }

    @Override
    public CacheManager getInstance(Object... parameters) {
        if (cacheManager == null) throw new IllegalStateException("JCSCacheToolFactory not initialized");
        return cacheManager;
    }

    @Override
    public void destroy() {
        // do nothing?
    }

    public ExecutionContextFactory getEcf() { return ecf; }
}
