package com.zmtech.zkit.context.runner;

import com.zmtech.zkit.context.ExecutionContext;
import com.zmtech.zkit.context.ExecutionContextFactory;
import com.zmtech.zkit.context.ScriptRunner;
import com.zmtech.zkit.context.impl.ExecutionContextFactoryImpl;
import com.zmtech.zkit.util.StringUtil;
import groovy.lang.Script;
import org.codehaus.groovy.runtime.InvokerHelper;
import javax.cache.Cache;

public class GroovyScriptRunner implements ScriptRunner {
    private ExecutionContextFactoryImpl ecfi;
    private Cache<String, Class> scriptGroovyLocationCache;

    GroovyScriptRunner() { }

    @Override
    public ScriptRunner init(ExecutionContextFactory ecf) {
        this.ecfi = (ExecutionContextFactoryImpl) ecf;
        this.scriptGroovyLocationCache = ecfi.getCache().getCache("resource.groovy.location", String.class, Class.class);
        return this;
    }

    @Override
    public Object run(String location, String method, ExecutionContext ec) {
        Script script = InvokerHelper.createScript(getGroovyByLocation(location), ec.getContextBinding());
        Object result;
        if (method != null && !method.isEmpty()) {
            result = script.invokeMethod(method, null);
        } else {
            result = script.run();
        }
        return result;
    }

    @Override
    public void destroy() { }

    public Class getGroovyByLocation(String location) {
        Class gc = (Class) scriptGroovyLocationCache.get(location);
        if (gc == null) gc = loadGroovy(location);
        return gc;
    }
    private synchronized Class loadGroovy(String location) {
        Class gc = (Class) scriptGroovyLocationCache.get(location);
        if (gc == null) {
            String groovyText = ecfi.getResource().getLocationText(location, false);
            gc = ecfi.compileGroovy(groovyText, StringUtil.cleanStringForJavaName(location));
            scriptGroovyLocationCache.put(location, gc);
        }
        return gc;
    }
}
