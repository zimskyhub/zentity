package com.zmtech.zframework.context.renderer;

import com.zmtech.zframework.context.ExecutionContextFactory;
import com.zmtech.zframework.context.TemplateRenderer;
import com.zmtech.zframework.context.impl.ExecutionContextFactoryImpl;
import groovy.lang.Writable;
import groovy.text.GStringTemplateEngine;
import groovy.text.Template;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.cache.Cache;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;


public class GStringTemplateRenderer implements TemplateRenderer {
    protected final static Logger logger = LoggerFactory.getLogger(GStringTemplateRenderer.class);

    protected ExecutionContextFactoryImpl ecfi;
    protected Cache<String, Template> templateGStringLocationCache;

    public GStringTemplateRenderer() { }

    public TemplateRenderer init(ExecutionContextFactory ecf) {
        this.ecfi = (ExecutionContextFactoryImpl) ecf;
        this.templateGStringLocationCache = ecfi.getCache().getCache("resource.gstring.location", String.class, Template.class);
        return this
    }

    public void render(String location, Writer writer) {
        Template theTemplate = getGStringTemplateByLocation(location);
        Writable writable = theTemplate.make(ecfi.getExecutionContext().getContext());
        try {
            writable.writeTo(writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public String stripTemplateExtension(String fileName) {
        return fileName.contains(".gstring") ? fileName.replace(".gstring", "") : fileName
    }

    public void destroy() { }

    public Template getGStringTemplateByLocation(String location) {
        Template theTemplate;
        if (templateGStringLocationCache instanceof MCache) {
            MCache<String, Template> mCache = (MCache) templateGStringLocationCache;
            ResourceReference rr = ecfi.resourceFacade.getLocationReference(location);
            long lastModified = rr != null ? rr.getLastModified() : 0L;
            theTemplate = mCache.get(location, lastModified);
        } else {
            // TODO: doesn't support on the fly reloading without cache expire/clear!
            theTemplate = templateGStringLocationCache.get(location);
        }
        if (!theTemplate) theTemplate = makeGStringTemplate(location)
        if (!theTemplate) throw new BaseArtifactException("Could not find template at [${location}]")
        return theTemplate
    }
    protected Template makeGStringTemplate(String location) {
        Template theTemplate = (Template) templateGStringLocationCache.get(location)
        if (theTemplate) return theTemplate

        Template newTemplate = null
        Reader templateReader = null
        try {
            templateReader = new InputStreamReader(ecfi.resourceFacade.getLocationStream(location))
            GStringTemplateEngine gste = new GStringTemplateEngine()
            newTemplate = gste.createTemplate(templateReader)
        } catch (Exception e) {
            throw new BaseArtifactException("Error while initializing template at [${location}]", e)
        } finally {
            if (templateReader != null) templateReader.close()
        }

        if (newTemplate) templateGStringLocationCache.put(location, newTemplate)
        return newTemplate
    }

}
