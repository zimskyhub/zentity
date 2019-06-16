package com.zmtech.zframework.actions;

import com.zmtech.zframework.context.impl.ExecutionContextFactoryImpl;
import com.zmtech.zframework.context.impl.ExecutionContextImpl;
import com.zmtech.zframework.exception.EntityException;
import com.zmtech.zframework.util.MNode;
import com.zmtech.zframework.util.StringUtil;
import freemarker.core.Environment;
import groovy.lang.Script;
import org.codehaus.groovy.runtime.DefaultGroovyMethods;
import org.codehaus.groovy.runtime.InvokerHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.StringWriter;
import java.io.Writer;
import java.util.HashMap;
import java.util.Map;

public class XmlAction {
    private static final Logger logger = LoggerFactory.getLogger(XmlAction.class);
    private static final boolean isDebugEnabled = logger.isDebugEnabled();

    protected final ExecutionContextFactoryImpl ecfi;

    private final MNode xmlNode;
    protected final String location;
    /** 使用FTL模板的XML action 已完成编译的Groovy类.*/
    private Class groovyClassInternal = null;

    public XmlAction(ExecutionContextFactoryImpl ecfi, MNode xmlNode, String location) {
        this.ecfi = ecfi;
        this.xmlNode = xmlNode;
        this.location = location;
    }

    public XmlAction(ExecutionContextFactoryImpl ecfi, String xmlText, String location) {
        this.ecfi = ecfi;
        this.location = location;
        if (xmlText != null && !xmlText.isEmpty()) {
            xmlNode = MNode.parseText(location, xmlText);
        } else {
            xmlNode = MNode.parseText(location, ecfi.getResource().getLocationText(location, false));
        }
    }

    /** 在 ExecutionContext 的当前上下文中运行XML操作 */
    public Object run(ExecutionContextImpl eci) {
        Class curClass = getGroovyClass();
        if (curClass == null) throw new IllegalStateException("没有适用于XML操作的Groovy类，请在日志中查看初始化的错误");
        if (isDebugEnabled) logger.debug("运行groovy脚本: \n" + writeGroovyWithLines() + "\n");

        Script script = InvokerHelper.createScript(curClass, eci.contextBindingInternal);
        try {
            return script.run();
        } catch (Throwable t) {
            // NOTE: not logging full stack trace, only needed when lots of threads are running to pin down error (always logged later)
            String tString = t.toString();
            if (!tString.contains("org.eclipse.jetty.io.EofException"))
                logger.error("运行groovy脚本时出错: (" + t.toString() + "): \n" + writeGroovyWithLines() + "\n");
            throw t;
        }
    }

    public boolean checkCondition(ExecutionContextImpl eci) {
        Object result = run(eci);
        if (result == null) return false;
        return DefaultGroovyMethods.asType(run(eci), Boolean.class);
    }

    // 用于工具屏幕，必须是公开的
    private String writeGroovyWithLines() {
        String groovyString = getGroovyString();
        StringBuilder groovyWithLines = new StringBuilder();
        int lineNo = 1;
        for (String line : groovyString.split("\n")) groovyWithLines.append(lineNo++).append(" : ").append(line).append("\n");
        return groovyWithLines.toString();
    }

    private Class getGroovyClass() {
        if (groovyClassInternal != null) return groovyClassInternal;
        return makeGroovyClass();
    }
    private synchronized Class makeGroovyClass() {
        if (groovyClassInternal != null) return groovyClassInternal;
        String curGroovy = getGroovyString();
        // if (logger.isTraceEnabled()) logger.trace("Xml Action [${location}] groovyString: ${curGroovy}")
        try {
            groovyClassInternal = ecfi.compileGroovy(curGroovy, StringUtil.cleanStringForJavaName(location));
        } catch (Throwable t) {
            groovyClassInternal = null;
            logger.error("解析 groovy 时出错,位于: [" + location + "]:\n" + writeGroovyWithLines() + "\n");
            throw t;
        }
        return groovyClassInternal;
    }

    private String getGroovyString() {
        // transform XML to groovy
        String groovyString;
        try {
            Map<String, Object> root = new HashMap<>(1);
            root.put("xmlActionsRoot", xmlNode);

            Writer outWriter = new StringWriter();
            Environment env = ecfi.getResource().getXmlActionsScriptRunner().getXmlActionsTemplate().createProcessingEnvironment(root, outWriter);
            env.process();

            groovyString = outWriter.toString();
        } catch (Exception e) {
            logger.error("读取 XML action时出错,位于: [" + location + "], text: " + xmlNode.toString());
            throw new EntityException("读取 XML action 作时出错,位于: [" + location + "]", e);
        }

        if (logger.isTraceEnabled()) logger.trace("XML actions 位于: [" + location + "] "+"\n从 xml节点: " + xmlNode.toString() +" 输出了groovy脚本:\n" + groovyString);

        return groovyString;
    }
}
