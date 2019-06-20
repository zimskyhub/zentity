package com.zmtech.zkit.context.renderer;

import com.zmtech.zkit.cache.impl.ZCache;
import com.zmtech.zkit.context.ExecutionContextFactory;
import com.zmtech.zkit.context.TemplateRenderer;
import com.zmtech.zkit.context.impl.ExecutionContextFactoryImpl;
import com.zmtech.zkit.exception.BaseException;
import com.zmtech.zkit.resource.references.ResourceReference;
import freemarker.core.Environment;
import freemarker.core.InvalidReferenceException;
import freemarker.ext.beans.BeansWrapper;
import freemarker.ext.beans.BeansWrapperBuilder;
import freemarker.template.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.cache.Cache;
import java.io.*;
import java.util.Locale;

public class FtlTemplateRenderer implements TemplateRenderer {
    public static final Version FTL_VERSION = Configuration.VERSION_2_3_25;
    private static final Logger logger = LoggerFactory.getLogger(FtlTemplateRenderer.class);

    protected ExecutionContextFactoryImpl ecfi;
    private Configuration defaultFtlConfiguration;
    private Cache<String, Template> templateFtlLocationCache;

    public FtlTemplateRenderer() { }

    @SuppressWarnings("unchecked")
    public TemplateRenderer init(ExecutionContextFactory ecf) {
        this.ecfi = (ExecutionContextFactoryImpl) ecf;
        defaultFtlConfiguration = makeFtlConfiguration(ecfi);
        templateFtlLocationCache = ecfi.getCache().getCache("resource.ftl.location", String.class, Template.class);
        return this;
    }

    public void render(String location, Writer writer) {
        Template theTemplate = getFtlTemplateByLocation(location);
        try {
            theTemplate.createProcessingEnvironment(ecfi.getEci().contextStack, writer).process();
        } catch (Exception e) { throw new BaseException("Error rendering template at " + location, e); }
    }
    public String stripTemplateExtension(String fileName) { return fileName.contains(".ftl") ? fileName.replace(".ftl", "") : fileName; }

    public void destroy() { }

    @SuppressWarnings("unchecked")
    private Template getFtlTemplateByLocation(final String location) {
        boolean hasVersion = location.indexOf("#") > 0;
        Template theTemplate = null;
        if (!hasVersion) {
            if (templateFtlLocationCache instanceof ZCache) {
                ZCache<String, Template> mCache = (ZCache) templateFtlLocationCache;
                ResourceReference rr = ecfi.getResource().getLocationReference(location);
                // if we have a rr and last modified is newer than the cache entry then throw it out (expire when cached entry
                //     updated time is older/less than rr.lastModified)
                long lastModified = rr != null ? rr.getLastModified() : 0L;
                theTemplate = mCache.get(location, lastModified);
            } else {
                // TODO: doesn't support on the fly reloading without cache expire/clear!
                theTemplate = templateFtlLocationCache.get(location);
            }
        }
        if (theTemplate == null) theTemplate = makeTemplate(location, hasVersion);
        if (theTemplate == null) throw new BaseException("Could not find template at " + location);
        return theTemplate;
    }

    private Template makeTemplate(final String location, boolean hasVersion) {
        if (!hasVersion) {
            Template theTemplate = templateFtlLocationCache.get(location);
            if (theTemplate != null) return theTemplate;
        }

        Template newTemplate;
        Reader templateReader = null;
        try {
            templateReader = new InputStreamReader(ecfi.getResource().getLocationStream(location), "UTF-8");
            newTemplate = new Template(location, templateReader, getFtlConfiguration());
        } catch (Exception e) {
            throw new BaseException("Error while initializing template at " + location, e);
        } finally {
            if (templateReader != null) {
                try { templateReader.close(); }
                catch (Exception e) { logger.error("Error closing template reader", e); }
            }
        }

        if (!hasVersion) templateFtlLocationCache.put(location, newTemplate);
        return newTemplate;
    }

    public Configuration getFtlConfiguration() { return defaultFtlConfiguration; }

    private static Configuration makeFtlConfiguration(ExecutionContextFactoryImpl ecfi) {
        Configuration newConfig = new MoquiConfiguration(FTL_VERSION, ecfi);
        BeansWrapper defaultWrapper = new BeansWrapperBuilder(FTL_VERSION).build();
        newConfig.setObjectWrapper(defaultWrapper);
        newConfig.setSharedVariable("Static", defaultWrapper.getStaticModels());

        // not needed, using getTemplate override instead: newConfig.setCacheStorage(new NullCacheStorage())
        // not needed, using getTemplate override instead: newConfig.setTemplateUpdateDelay(1)
        // not needed, using getTemplate override instead: newConfig.setTemplateLoader(new MoquiResourceTemplateLoader(ecfi))
        // not needed, using getTemplate override instead: newConfig.setLocalizedLookup(false)

        newConfig.setTemplateExceptionHandler(new MoquiTemplateExceptionHandler());
        newConfig.setLogTemplateExceptions(false);
        newConfig.setWhitespaceStripping(true);
        newConfig.setDefaultEncoding("UTF-8");
        return newConfig;
    }

    private static class MoquiConfiguration extends Configuration {
        private ExecutionContextFactoryImpl ecfi;
        MoquiConfiguration(Version version, ExecutionContextFactoryImpl ecfi) {
            super(version);
            this.ecfi = ecfi;
        }

        @Override
        public Template getTemplate(final String name, Locale locale, Object customLookupCondition, String encoding,
                                    boolean parseAsFTL, boolean ignoreMissing) throws IOException {
            //return super.getTemplate(name, locale, encoding, parse)
            // NOTE: doing this because template loading behavior with cache/etc not desired and was having issues
            Template theTemplate;
            if (parseAsFTL) {
                theTemplate = ecfi.getResource().getFtlTemplateRenderer().getFtlTemplateByLocation(name);
            } else {
                String text = ecfi.getResource().getLocationText(name, true);
                theTemplate = Template.getPlainTextTemplate(name, text, this);
            }

            // NOTE: this is the same exception the standard FreeMarker code returns
            if (theTemplate == null && !ignoreMissing) throw new FileNotFoundException("Template " + name + " not found.");
            return theTemplate;
        }

        public ExecutionContextFactoryImpl getEcfi() { return ecfi; }
        public void setEcfi(ExecutionContextFactoryImpl ecfi) { this.ecfi = ecfi; }
    }

    private static class MoquiTemplateExceptionHandler implements TemplateExceptionHandler {
        public void handleTemplateException(final TemplateException te, Environment env, Writer out) throws TemplateException {
            try {
                // TODO: encode error, something like: StringUtil.SimpleEncoder simpleEncoder = FreeMarkerWorker.getWrappedObject("simpleEncoder", env);
                // stackTrace = simpleEncoder.encode(stackTrace);
                if (te.getCause() != null) {
                    BaseException.filterStackTrace(te.getCause());
                    logger.error("Error from code called in FTL render", te.getCause());
                    // NOTE: ScreenTestImpl looks for this string, ie "[Template Error"
                    String causeMsg = te.getCause().getMessage();
                    if (causeMsg == null || causeMsg.isEmpty()) causeMsg = te.getMessage();
                    if (causeMsg == null || causeMsg.isEmpty()) causeMsg = "no message available";
                    out.write("[Template Error: ");
                    out.write(causeMsg);
                    out.write("]");
                } else {
                    // NOTE: if there is not cause it is an exception generated by FreeMarker and not some code called in the template
                    if (te instanceof InvalidReferenceException) {
                        // NOTE: ScreenTestImpl looks for this string, ie "[Template Error"
                        logger.error("[Template Error: expression '" + te.getBlamedExpressionString() + "' was null or not found (" + te.getTemplateSourceName() + ":" + te.getLineNumber() + "," + te.getColumnNumber() + ")]");
                        out.write("[Template Error]");
                    } else {
                        BaseException.filterStackTrace(te);
                        logger.error("Error from FTL in render", te);
                        // NOTE: ScreenTestImpl looks for this string, ie "[Template Error"
                        out.write("[Template Error: ");
                        out.write(te.getMessage());
                        out.write("]");
                    }
                }
            } catch (IOException e) {
                throw new TemplateException("Failed to print error message. Cause: " + e, env);
            }
        }
    }
}
