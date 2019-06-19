package com.zmtech.zkit.context.runner;

import com.zmtech.zkit.actions.XmlAction;
import com.zmtech.zkit.context.ExecutionContext;
import com.zmtech.zkit.context.ExecutionContextFactory;
import com.zmtech.zkit.context.ScriptRunner;
import com.zmtech.zkit.context.impl.ExecutionContextFactoryImpl;
import com.zmtech.zkit.context.impl.ExecutionContextImpl;
import freemarker.template.Template;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.cache.Cache;
import java.io.InputStreamReader;
import java.io.Reader;

public class XmlActionsScriptRunner implements ScriptRunner {
    protected final static Logger logger = LoggerFactory.getLogger(XmlActionsScriptRunner.class);

    protected ExecutionContextFactoryImpl ecfi;
    protected Cache<String, XmlAction> scriptXmlActionLocationCache;
    protected Template xmlActionsTemplate = null;

    public XmlActionsScriptRunner() { }

    public ScriptRunner init(ExecutionContextFactory ecf) {
        this.ecfi = (ExecutionContextFactoryImpl) ecf;
        this.scriptXmlActionLocationCache = ecfi.getCache().getCache("resource.xml-actions.location", String.class, XmlAction.class);
        return this
    }

    public Object run(String location, String method, ExecutionContext ec) {
        XmlAction xa = getXmlActionByLocation(location);
        return xa.run((ExecutionContextImpl) ec);
    }

    public void destroy() { }

    public XmlAction getXmlActionByLocation(String location) {
        XmlAction xa = (XmlAction) scriptXmlActionLocationCache.get(location);
        if (xa == null) xa = loadXmlAction(location);
        return xa;
    }
    protected synchronized XmlAction loadXmlAction(String location) {
        XmlAction xa =  scriptXmlActionLocationCache.get(location);
        if (xa == null) {
            xa = new XmlAction(ecfi, ecfi.getResource().getLocationText(location, false), location);
            scriptXmlActionLocationCache.put(location, xa);
        }
        return xa;
    }

    public Template getXmlActionsTemplate() {
        if (xmlActionsTemplate == null) makeXmlActionsTemplate();
        return xmlActionsTemplate;
    }
    protected synchronized void makeXmlActionsTemplate() {
        if (xmlActionsTemplate != null) return;

        String templateLocation = ecfi.getConfXmlRoot().first("resource-facade").attribute("xml-actions-template-location");
        Template newTemplate = null;
        Reader templateReader = null;
        try {
            templateReader = new InputStreamReader(ecfi.getResource().getLocationStream(templateLocation));
            newTemplate = new Template(templateLocation, templateReader,
                    ecfi.getResource().ftlTemplateRenderer.getFtlConfiguration());
        } catch (Exception e) {
            logger.error("Error while initializing XMLActions template at [${templateLocation}]", e);
        } finally {
            if (templateReader != null) templateReader.close();
        }
        xmlActionsTemplate = newTemplate;
    }
}
