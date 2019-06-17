package com.zmtech.zkit.tools.impl;

import com.zmtech.zkit.cache.impl.ZCacheManager;
import com.zmtech.zkit.context.ExecutionContextFactory;
import com.zmtech.zkit.tools.ToolFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.cache.CacheManager;

/** A factory for getting a MCacheManager */
public class ZCacheToolFactory implements ToolFactory<CacheManager> {
    protected final static Logger logger = LoggerFactory.getLogger(ZCacheToolFactory.class);
    public final static String TOOL_NAME = "MCache";

    protected ExecutionContextFactory ecf = null;

    private ZCacheManager cacheManager = null;

    /** Default empty constructor */
    public ZCacheToolFactory() { }

    @Override
    public String getName() { return TOOL_NAME; }
    @Override
    public void init(ExecutionContextFactory ecf) { }
    @Override
    public void preFacadeInit(ExecutionContextFactory ecf) {
        this.ecf = ecf;
        cacheManager = ZCacheManager.getMCacheManager();
    }

    @Override
    public CacheManager getInstance(Object... parameters) {
        if (cacheManager == null) throw new IllegalStateException("ZCacheToolFactory not initialized");
        return cacheManager;
    }

    @Override
    public void destroy() { cacheManager.close(); }

    public ExecutionContextFactory getEcf() { return ecf; }
}
