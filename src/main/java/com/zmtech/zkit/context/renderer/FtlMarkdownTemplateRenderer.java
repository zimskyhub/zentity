package com.zmtech.zkit.context.renderer;

import com.zmtech.zkit.cache.impl.ZCache;
import com.zmtech.zkit.context.ExecutionContextFactory;
import com.zmtech.zkit.context.TemplateRenderer;
import com.zmtech.zkit.context.impl.ExecutionContextFactoryImpl;
import com.zmtech.zkit.exception.BaseException;
import com.zmtech.zkit.resource.references.ResourceReference;
import freemarker.template.Template;
import org.pegdown.PegDownProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.cache.Cache;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;

public class FtlMarkdownTemplateRenderer implements TemplateRenderer {
    protected final static Logger logger = LoggerFactory.getLogger(FtlMarkdownTemplateRenderer.class);

    protected ExecutionContextFactoryImpl ecfi;
    protected Cache<String, Template> templateFtlLocationCache;

    public FtlMarkdownTemplateRenderer() { }

    public TemplateRenderer init(ExecutionContextFactory ecf) {
        this.ecfi = (ExecutionContextFactoryImpl) ecf;
        this.templateFtlLocationCache = ecfi.getCache().getCache("resource.ftl.location", String.class, Template.class);
        return this;
    }

    public void render(String location, Writer writer) {
        boolean hasVersion = location.indexOf("#") > 0;
        Template theTemplate = null;
        if (!hasVersion) {
            if (templateFtlLocationCache instanceof ZCache) {
                ZCache<String, Template> mCache = (ZCache) templateFtlLocationCache;
                ResourceReference rr = ecfi.getResource().getLocationReference(location);
                long lastModified = rr != null ? rr.getLastModified() : 0L;
                theTemplate = mCache.get(location, lastModified);
            } else {
                // TODO: doesn't support on the fly reloading without cache expire/clear!
                theTemplate = templateFtlLocationCache.get(location);
            }
        }
        if (theTemplate == null) theTemplate = makeTemplate(location, hasVersion);
        if (theTemplate == null) throw new BaseException("Could not find template at ${location}");
        try {
            theTemplate.createProcessingEnvironment(ecfi.getEci().contextStack, writer).process();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    protected Template makeTemplate(String location, boolean hasVersion) {
        if (!hasVersion) {
            Template theTemplate = (Template) templateFtlLocationCache.get(location);
            if (theTemplate != null) return theTemplate;
        }

        Template newTemplate;
        try {
            //ScreenRenderImpl sri = (ScreenRenderImpl) ecfi.getExecutionContext().getContext().get("sri")
            // how to set base URL? if (sri != null) builder.setBase(sri.getBaseLinkUri())
            /*
            Markdown4jProcessor markdown4jProcessor = new Markdown4jProcessor()
            String mdText = markdown4jProcessor.process(ecfi.resourceFacade.getLocationText(location, false))
            */

            PegDownProcessor pdp = new PegDownProcessor(MarkdownTemplateRenderer.pegDownOptions);
            String mdText = pdp.markdownToHtml(ecfi.getResource().getLocationText(location, false));

            // logger.warn("======== .md.ftl post-markdown text: ${mdText}")

            Reader templateReader = new StringReader(mdText);
            newTemplate = new Template(location, templateReader, ecfi.getResource().ftlTemplateRenderer.getFtlConfiguration());
        } catch (Exception e) {
            throw new BaseException("Error while initializing template at [${location}]", e);
        }

        if (!hasVersion && newTemplate != null) templateFtlLocationCache.put(location, newTemplate);
        return newTemplate;
    }

    public String stripTemplateExtension(String fileName) {
        String stripped = fileName.contains(".md") ? fileName.replace(".md", "") : fileName;
        stripped = stripped.contains(".markdown") ? stripped.replace(".markdown", "") : stripped;
        return stripped.contains(".ftl") ? stripped.replace(".ftl", "") : stripped;
    }

    public void destroy() { }
}
