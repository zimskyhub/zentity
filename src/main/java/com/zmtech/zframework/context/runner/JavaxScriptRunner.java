
package com.zmtech.zframework.context.runner;

import com.zmtech.zframework.context.ExecutionContext;
import com.zmtech.zframework.context.ExecutionContextFactory;
import com.zmtech.zframework.context.ScriptRunner;
import com.zmtech.zframework.context.impl.ExecutionContextFactoryImpl;
import com.zmtech.zframework.exception.BaseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.cache.Cache;
import javax.script.*;
import java.util.Map;


public class JavaxScriptRunner implements ScriptRunner {
    protected final static Logger logger = LoggerFactory.getLogger(JavaxScriptRunner.class);

    protected ScriptEngineManager mgr = new ScriptEngineManager();

    protected ExecutionContextFactoryImpl ecfi;
    protected Cache scriptLocationCache;
    protected String engineName;

    public JavaxScriptRunner() { this.engineName = "groovy"; }
    public JavaxScriptRunner(String engineName) { this.engineName = engineName; }

    public ScriptRunner init(ExecutionContextFactory ecf) {
        this.ecfi = (ExecutionContextFactoryImpl) ecf;
        this.scriptLocationCache = ecfi.getCache().getCache("resource.${engineName}.location");
        return this;
    }

    public Object run(String location, String method, ExecutionContext ec) {
        // this doesn't support methods, so if passed warn about that
        if (method!=null && !method.isEmpty()) logger.warn("Tried to invoke script at [${location}] with method [${method}] through javax.script (JSR-223) runner which does NOT support methods, so it is being ignored.", new BaseException("Script Run Location"));

        ScriptEngine engine = mgr.getEngineByName(engineName);
        return bindAndRun(location, ec, engine, scriptLocationCache);
    }

    public void destroy() { }

    public static Object bindAndRun(String location, ExecutionContext ec, ScriptEngine engine, Cache scriptLocationCache) {
        Bindings bindings = new SimpleBindings();
        for (Map.Entry ce : ec.getContext().entrySet()) bindings.put((String) ce.getKey(), ce.getValue());

        Object result = null;
        if (engine instanceof Compilable) {
            // cache the CompiledScript
            CompiledScript script = (CompiledScript) scriptLocationCache.get(location);
            if (script == null) {
                try {
                    script = ((Compilable) engine).compile(ec.getResource().getLocationText(location, false));
                } catch (ScriptException e) {
                    e.printStackTrace();
                }
                scriptLocationCache.put(location, script);
            }
            try {
                result = script.eval(bindings);
            } catch (ScriptException e) {
                e.printStackTrace();
            }
        } else {
            // cache the script text
            String scriptText = (String) scriptLocationCache.get(location);
            if (scriptText == null) {
                scriptText = ec.getResource().getLocationText(location, false);
                scriptLocationCache.put(location, scriptText);
            }
            try {
                result = engine.eval(scriptText, bindings);
            } catch (ScriptException e) {
                e.printStackTrace();
            }
        }

        return result;
    }
}
