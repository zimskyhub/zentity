package com.zmtech.zframework.resource.impl;

import com.zmtech.zframework.cache.impl.ZCache;
import com.zmtech.zframework.context.ScriptRunner;
import com.zmtech.zframework.context.TemplateRenderer;
import com.zmtech.zframework.context.impl.ExecutionContextFactoryImpl;
import com.zmtech.zframework.context.impl.ExecutionContextImpl;
import com.zmtech.zframework.context.reference.BaseResourceReference;
import com.zmtech.zframework.context.renderer.FtlTemplateRenderer;
import com.zmtech.zframework.context.runner.JavaxScriptRunner;
import com.zmtech.zframework.context.runner.XmlActionsScriptRunner;
import com.zmtech.zframework.entity.impl.EntityValueBase;
import com.zmtech.zframework.exception.BaseException;
import com.zmtech.zframework.resource.ResourceFacade;
import com.zmtech.zframework.tools.ToolFactory;
import com.zmtech.zframework.util.*;
import groovy.lang.Script;
import groovy.transform.CompileStatic;
import org.codehaus.groovy.runtime.InvokerHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.ContentHandler;

import javax.activation.DataSource;
import javax.cache.Cache;
import javax.jcr.*;
import javax.mail.util.ByteArrayDataSource;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.xml.transform.*;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.stream.StreamSource;
import java.io.*;
import java.net.URL;
import java.util.*;

public class ResourceFacadeImpl implements ResourceFacade {
    protected final static Logger logger = LoggerFactory.getLogger(ResourceFacadeImpl.class);

    protected final ExecutionContextFactoryImpl ecfi;

    public final FtlTemplateRenderer ftlTemplateRenderer;
    public final XmlActionsScriptRunner xmlActionsScriptRunner;

    // the groovy Script object is not thread safe, so have one per thread per expression; can be reused as thread is reused
    protected final ThreadLocal<Map<String, Script>> threadScriptByExpression = new ThreadLocal<>();
    protected final Map<String, Class> scriptGroovyExpressionCache = new HashMap<>();

    protected final Cache<String, String> textLocationCache;
    protected final Cache<String, ResourceReference> resourceReferenceByLocation;

    protected final Map<String, Class> resourceReferenceClasses = new HashMap<>();
    protected final Map<String, TemplateRenderer> templateRenderers = new HashMap<>();
    protected final ArrayList<String> templateRendererExtensions = new ArrayList<>();
    protected final ArrayList<Integer> templateRendererExtensionsDots = new ArrayList<>();
    protected final Map<String, ScriptRunner> scriptRunners = new HashMap<>();
    protected final ScriptEngineManager scriptEngineManager = new ScriptEngineManager();
    protected final ToolFactory<org.xml.sax.ContentHandler> xslFoHandlerFactory;

    protected final Map<String, Repository> contentRepositories = new HashMap<>();
    protected final ThreadLocal<Map<String, Session>> contentSessions = new ThreadLocal<>();

    public ResourceFacadeImpl(ExecutionContextFactoryImpl ecfi) {
        this.ecfi = ecfi;

        ftlTemplateRenderer = new FtlTemplateRenderer();
        ftlTemplateRenderer.init(ecfi);

        xmlActionsScriptRunner = new XmlActionsScriptRunner();
        xmlActionsScriptRunner.init(ecfi);

        textLocationCache = ecfi.getCache().getCache("resource.text.location", String.class, String.class);
        // a plain HashMap is faster and just fine here: scriptGroovyExpressionCache = ecfi.cacheFacade.getCache("resource.groovy.expression")
        resourceReferenceByLocation = ecfi.getCache().getCache("resource.reference.location", String.class, ResourceReference.class);

        MNode resourceFacadeNode = ecfi.getConfXmlRoot().first("resource-facade");

        // Setup resource reference classes
        for (MNode rrNode : resourceFacadeNode.children("resource-reference")) {
            try {
                Class rrClass = Thread.currentThread().getContextClassLoader().loadClass(rrNode.attribute("class"));
                resourceReferenceClasses.put(rrNode.attribute("scheme"), rrClass);
            } catch (ClassNotFoundException e) {
                logger.info("Class [" + rrNode.attribute("class") + "] not found (" + e.toString() + ")");
            }
        }

        // Setup template renderers
        for (MNode templateRendererNode : resourceFacadeNode.children("template-renderer")) {
            TemplateRenderer tr = null;
            try {
                tr = (TemplateRenderer) Thread.currentThread().getContextClassLoader()
                        .loadClass(templateRendererNode.attribute("class")).newInstance();
            } catch (Exception e) {
                e.printStackTrace();
            }
            templateRenderers.put(templateRendererNode.attribute("extension"), tr.init(ecfi));
        }
        for (String ext : templateRenderers.keySet()) {
            templateRendererExtensions.add(ext);
            templateRendererExtensionsDots.add(ObjectUtil.countChars(ext, (char) '.'));
        }

        // Setup script runners
        for (MNode scriptRunnerNode : resourceFacadeNode.children("script-runner")) {
            if (scriptRunnerNode.attribute("class") != null) {
                ScriptRunner sr = null;
                try {
                    sr = (ScriptRunner) Thread.currentThread().getContextClassLoader()
                            .loadClass(scriptRunnerNode.attribute("class")).newInstance();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                scriptRunners.put(scriptRunnerNode.attribute("extension"), sr.init(ecfi));
            } else if (scriptRunnerNode.attribute("engine") != null) {
                ScriptRunner sr = new JavaxScriptRunner(scriptRunnerNode.attribute("engine")).init(ecfi);
                scriptRunners.put(scriptRunnerNode.attribute("extension"), sr);
            } else {
                logger.error("Configured script-runner for extension [" + scriptRunnerNode.attribute("extension") + "] must have either a class or engine attribute and has neither.");
            }
        }

        // Get XSL-FO Handler Factory
        if (resourceFacadeNode.attribute("xsl-fo-handler-factory") != null) {
            xslFoHandlerFactory = ecfi.getToolFactory(resourceFacadeNode.attribute("xsl-fo-handler-factory"));
            if (xslFoHandlerFactory != null) {
                logger.info("Using xsl-fo-handler-factory " + resourceFacadeNode.attribute("xsl-fo-handler-factory") + " (" + xslFoHandlerFactory.getClass().getName() + ")");
            } else {
                logger.warn("Could not find xsl-fo-handler-factory with name " + resourceFacadeNode.attribute("xsl-fo-handler-factory"));
            }
        } else {
            xslFoHandlerFactory = null;
        }

        // Setup content repositories
        for (MNode repositoryNode : ecfi.getConfXmlRoot().first("repository-list").children("repository")) {
            String repoName = repositoryNode.attribute("name");
            Repository repo = null;
            Map<String, String> parameters = new HashMap<>();
            for (MNode paramNode : repositoryNode.children("init-param"))
                parameters.put(paramNode.attribute("name"), paramNode.attribute("value"));

            try {
                for (RepositoryFactory factory : ServiceLoader.load(RepositoryFactory.class)) {
                    repo = factory.getRepository(parameters);
                    // factory accepted parameters
                    if (repo != null) break;
                }
                if (repo != null) {
                    contentRepositories.put(repoName, repo);
                    logger.info("Added JCR Repository " + repoName + " of type " + repo.getClass().getName() + " for workspace " + repositoryNode.attribute("workspace") + " using parameters: " + parameters);
                } else {
                    logger.error("Could not find JCR RepositoryFactory for repository " + repoName + " using parameters: " + parameters);
                }
            } catch (Exception e) {
                logger.error("Error getting JCR Repository " + repositoryNode.attribute("name") + ": " + e.toString());
            }
        }
    }

    public void destroyAllInThread() {
        Map<String, Session> sessionMap = contentSessions.get();
        if (sessionMap != null && !sessionMap.isEmpty())
            for (Session openSession : sessionMap.values()) openSession.logout();
        contentSessions.remove();
    }

    public ExecutionContextFactoryImpl getEcfi() {
        return this.ecfi;
    }

    public Map<String, TemplateRenderer> getTemplateRenderers() {
        return Collections.unmodifiableMap(this.templateRenderers);
    }

    public TreeSet<String> getTemplateRendererExtensionSet() {
        return new TreeSet<>(templateRendererExtensions);
    }

    public Repository getContentRepository(String name) {
        return contentRepositories.get(name);
    }

    /**
     * Get the active JCR Session for the context/thread, making sure it is live, and make one if needed.
     */
    public Session getContentRepositorySession(String name) {
        Map<String, Session> sessionMap = contentSessions.get();
        if (sessionMap == null) {
            sessionMap = new HashMap<>();
            contentSessions.set(sessionMap);
        }
        Session newSession = sessionMap.get("name");
        if (newSession != null) {
            if (newSession.isLive()) {
                return newSession;
            } else {
                sessionMap.remove(name);
                // newSession = null
            }
        }

        Repository rep = contentRepositories.get("name");
        if (rep == null) return null;
        MNode repositoryNode = ecfi.getConfXmlRoot().first("repository-list")
                .first((MNode it) -> {
                    return it.getName().equals("repository") && it.attribute("name").equals(name);
                });
        SimpleCredentials credentials = new SimpleCredentials(repositoryNode.attribute("username") != null ? repositoryNode.attribute("username") : "anonymous",
                (repositoryNode.attribute("password") != null ? repositoryNode.attribute("password") : "").toCharArray());
        if (repositoryNode.attribute("workspace") != null) {
            try {
                newSession = rep.login(credentials, repositoryNode.attribute("workspace"));
            } catch (RepositoryException e) {
                e.printStackTrace();
            }
        } else {
            try {
                newSession = rep.login(credentials);
            } catch (RepositoryException e) {
                e.printStackTrace();
            }
        }

        if (newSession != null) sessionMap.put(name, newSession);
        return newSession;
    }

    @Override
    public ResourceReference getLocationReference(String location) {
        if (location == null) return null;

        // version ignored for this call, just strip it
        int hashIdx = location.indexOf("#");
        if (hashIdx > 0) location = location.substring(0, hashIdx);

        ResourceReference cachedRr = resourceReferenceByLocation.get(location);
        if (cachedRr != null) return cachedRr;

        String scheme = getLocationScheme(location);
        Class rrClass = resourceReferenceClasses.get(scheme);
        if (rrClass == null)
            throw new BaseException("Prefix (" + scheme + ") not supported for location [" + location + "]");

        ResourceReference rr = null;
        try {
            rr = (ResourceReference) rrClass.newInstance();
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (rr != null) rr.init(location);
        resourceReferenceByLocation.put(location, rr);
        return rr;
    }

    public static String getLocationScheme(String location) {
        String scheme = "file";
        // Q: how to get the scheme for windows? the Java URI class doesn't like spaces, the if we look for the first ":"
        //    it may be a drive letter instead of a scheme/protocol
        // A: ignore colon if only one character before it
        if (location.indexOf(":") > 1) {
            String prefix = location.substring(0, location.indexOf(":"));
            if (!prefix.contains("/") && prefix.length() > 2) scheme = prefix;
        }
        return scheme;
    }

    @Override
    public InputStream getLocationStream(String location) {
        int hashIdx = location.indexOf("#");
        String versionName = null;
        if (hashIdx > 0) {
            if ((hashIdx + 1) < location.length()) versionName = location.substring(hashIdx + 1);
            location = location.substring(0, hashIdx);
        }

        ResourceReference rr = getLocationReference(location);
        if (rr == null) return null;
        return rr.openStream(versionName);
    }

    @Override
    public String getLocationText(String location, boolean cache) {
        int hashIdx = location.indexOf("#");
        String versionName = (hashIdx > 0 && (hashIdx + 1) < location.length()) ? location.substring(hashIdx + 1) : null;

        ResourceReference textRr = getLocationReference(location);
        if (textRr == null) {
            logger.info("Cound not get resource reference for location [" + location + "], returning empty location text String");
            return "";
        }
        // don't cache when getting by version
        if (versionName != null) cache = false;
        if (cache) {
            String cachedText;
            if (textLocationCache instanceof ZCache) {
                ZCache<String, String> mCache = (ZCache<String, String>) textLocationCache;
                // if we have a rr and last modified is newer than the cache entry then throw it out (expire when cached entry
                //     updated time is older/less than rr.lastModified)
                cachedText = (String) mCache.get(location, textRr.getLastModified());
            } else {
                // TODO: doesn't support on the fly reloading without cache expire/clear!
                cachedText = (String) textLocationCache.get(location);
            }
            if (cachedText != null) return cachedText;
        }
        InputStream locStream = textRr.openStream(versionName);
        if (locStream == null) logger.info("Cannot get text, no resource found at location [${location}]");
        String text = ObjectUtil.getStreamText(locStream);
        if (cache) textLocationCache.put(location, text);
        // logger.warn("==== getLocationText at ${location} version ${versionName} text ${text.length() > 100 ? text.substring(0, 100) : text}")
        return text;
    }

    @Override
    public DataSource getLocationDataSource(String location) {
        int hashIdx = location.indexOf("#");
        String versionName = null;
        if (hashIdx > 0) {
            if ((hashIdx + 1) < location.length()) versionName = location.substring(hashIdx + 1);
            location = location.substring(0, hashIdx);
        }

        ResourceReference fileResourceRef = getLocationReference(location);
        TemplateRenderer tr = getTemplateRendererByLocation(fileResourceRef.getLocation());

        String fileName = fileResourceRef.getFileName();
        // strip template extension(s) to avoid problems with trying to find content types based on them
        String fileContentType = getContentType(tr != null ? tr.stripTemplateExtension(fileName) : fileName);

        boolean isBinary = ResourceReference.isBinaryContentType(fileContentType);

        if (isBinary) {
            try {
                return new ByteArrayDataSource(fileResourceRef.openStream(versionName), fileContentType);
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            // not a binary object (hopefully), get the text and pass it over
            if (tr != null) {
                // NOTE: version ignored here
                StringWriter sw = new StringWriter();
                tr.render(fileResourceRef.getLocation(), sw);
                try {
                    return new ByteArrayDataSource(sw.toString(), fileContentType);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } else {
                // no renderer found, just grab the text (cached) and throw it to the writer
                String textLoc = fileResourceRef.getLocation();
                if (versionName != null && !versionName.isEmpty()) textLoc = textLoc.concat("#").concat(versionName);
                String text = getLocationText(textLoc, true);
                try {
                    return new ByteArrayDataSource(text, fileContentType);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        return null;
    }

    @Override
    public void template(String location, Writer writer) {
        // NOTE: let version fall through to tr.render() and getLocationText()
        TemplateRenderer tr = getTemplateRendererByLocation(location);
        if (tr != null) {
            tr.render(location, writer);
        } else {
            // no renderer found, just grab the text and throw it to the writer
            String text = getLocationText(location, true);
            if (text != null && !text.isEmpty()) {
                try {
                    writer.write(text);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public static final Set<String> binaryExtensions = new HashSet<>(Arrays.asList("png", "jpg", "jpeg", "gif", "pdf", "doc", "docx", "xsl", "xslx"));

    public TemplateRenderer getTemplateRendererByLocation(String location) {
        int hashIdx = location.indexOf("#");
        if (hashIdx > 0) location = location.substring(0, hashIdx);

        // match against extension for template renderer, with as many dots that match as possible (most specific match)
        int lastSlashIndex = location.lastIndexOf("/");
        int dotIndex = location.indexOf(".", lastSlashIndex);
        String fullExt = location.substring(dotIndex + 1);
        TemplateRenderer tr = (TemplateRenderer) templateRenderers.get(fullExt);
        if (tr != null || templateRenderers.containsKey(fullExt)) return tr;

        int lastDotIndex = location.lastIndexOf(".", lastSlashIndex);
        String lastExt = location.substring(lastDotIndex + 1);
        if (binaryExtensions.contains(lastExt)) {
            templateRenderers.put(fullExt, null);
            return null;
        }

        int mostDots = -1;
        int templateRendererExtensionsSize = templateRendererExtensions.size();
        for (int i = 0; i < templateRendererExtensionsSize; i++) {
            String ext = (String) templateRendererExtensions.get(i);
            if (location.endsWith(ext)) {
                int dots = templateRendererExtensionsDots.get(i).intValue();
                if (dots > mostDots) {
                    mostDots = dots;
                    tr = (TemplateRenderer) templateRenderers.get(ext);
                }
            }
        }
        // if there is no template renderer for extension remember that
        if (tr == null) {
            // logger.warn("No renderer found for ${location}, exts: ${templateRendererExtensions}\ntemplateRenderers: ${templateRenderers}")
            templateRenderers.put(fullExt, null);
        }
        return tr;
    }

    @Override
    public Object script(String location, String method) {
        int hashIdx = location.indexOf("#");
        if (hashIdx > 0) location = location.substring(0, hashIdx);
        // NOTE: version ignored here

        ExecutionContextImpl ec = ecfi.getEci();
        String extension = location.substring(location.lastIndexOf("."));
        ScriptRunner sr = scriptRunners.get(extension);

        if (sr != null) {
            return sr.run(location, method, ec);
        } else {
            // see if the extension is known
            ScriptEngine engine = scriptEngineManager.getEngineByExtension(extension);
            if (engine == null)
                throw new BaseException("Cannot run script [" + location + "], unknown extension (not in ZFrameWork Conf file, and unkown to Java ScriptEngineManager).");
            return JavaxScriptRunner.bindAndRun(location, ec, engine, ecfi.getCache().getCache("resource.script${extension}.location"));
        }
    }

    @Override
    public Object script(String location, String method, Map<String, Object> additionalContext) {
        ExecutionContextImpl ec = ecfi.getEci();
        ContextStack cs = ec.contextStack;
        boolean doPushPop = additionalContext != null && additionalContext.size() > 0;
        try {
            if (doPushPop) {
                if (additionalContext instanceof EntityValueBase)
                    cs.push(((EntityValueBase) additionalContext).getValueMap());
                else cs.push(additionalContext);
                // do another push so writes to the context don't modify the passed in Map
                cs.push();
            }
            return script(location, method);
        } finally {
            if (doPushPop) {
                cs.pop();
                cs.pop();
            }
        }
    }

    public Object setInContext(String field, String from, String value, String defaultValue, String type, String setIfEmpty) {
        Object tempValue = getValueFromContext(from, value, defaultValue, type);
        ecfi.getEci().contextStack.put("_tempValue", tempValue);
        if (tempValue != null || (setIfEmpty != null && !setIfEmpty.isEmpty())) expression(field + " = _tempValue", "");

        return tempValue;
    }

    public Object getValueFromContext(String from, String value, String defaultValue, String type) {
        Object tempValue = from != null && !from.isEmpty() ? expression(from, "") : expand(value, "", null, false);
        if (tempValue == null && defaultValue != null && !defaultValue.isEmpty())
            tempValue = expand(defaultValue, "", null, false);
        if (type != null && !type.isEmpty()) tempValue = ObjectUtil.basicConvert(tempValue, type);
        return tempValue;
    }

    @Override
    public boolean condition(String expression, String debugLocation) {
        return conditionInternal(expression, debugLocation, ecfi.getEci());
    }

    protected boolean conditionInternal(String expression, String debugLocation, ExecutionContextImpl ec) {
        if (expression == null || expression.isEmpty()) return false;
        try {
            Script script = getGroovyScript(expression, ec);
            Object result = script.run();
            script.setBinding(null);
            return (Boolean) result;
        } catch (Exception e) {
            throw new BaseException("Error in condition [" + expression + "] from [" + debugLocation + "]", e);
        }
    }

    @Override
    public boolean condition(String expression, String debugLocation, Map<String, Object> additionalContext) {
        ExecutionContextImpl ec = ecfi.getEci();
        ContextStack cs = ec.contextStack;
        boolean doPushPop = additionalContext != null && additionalContext.size() > 0;
        try {
            if (doPushPop) {
                if (additionalContext instanceof EntityValueBase)
                    cs.push(((EntityValueBase) additionalContext).getValueMap());
                else cs.push(additionalContext);
                // do another push so writes to the context don't modify the passed in Map
                cs.push();
            }
            return conditionInternal(expression, debugLocation, ec);
        } finally {
            if (doPushPop) {
                cs.pop();
                cs.pop();
            }
        }
    }

    @Override
    public Object expression(String expression, String debugLocation) {
        return expressionInternal(expression, debugLocation, ecfi.getEci());
    }

    protected Object expressionInternal(String expression, String debugLocation, ExecutionContextImpl ec) {
        if (expression == null || expression.isEmpty()) return null;
        try {
            Script script = getGroovyScript(expression, ec);
            Object result = script.run();
            script.setBinding(null);
            return result;
        } catch (Exception e) {
            throw new BaseException("Error in field expression [${expression}] from [${debugLocation}]", e);
        }
    }

    @Override
    public Object expression(String expr, String debugLocation, Map<String, Object> additionalContext) {
        ExecutionContextImpl ec = ecfi.getEci();
        ContextStack cs = ec.contextStack;
        boolean doPushPop = additionalContext != null && additionalContext.size() > 0;
        try {
            if (doPushPop) {
                if (additionalContext instanceof EntityValueBase)
                    cs.push(((EntityValueBase) additionalContext).getValueMap());
                else cs.push(additionalContext);
                // do another push so writes to the context don't modify the passed in Map
                cs.push();
            }
            return expressionInternal(expr, debugLocation, ec);
        } finally {
            if (doPushPop) {
                cs.pop();
                cs.pop();
            }
        }
    }


    @Override
    public String expandNoL10n(String inputString, String debugLocation) {
        return expand(inputString, debugLocation, null, false);
    }

    @Override
    public String expand(String inputString, String debugLocation) {
        return expand(inputString, debugLocation, null, true);
    }

    @Override
    public String expand(String inputString, String debugLocation, Map<String, Object> additionalContext) {
        return expand(inputString, debugLocation, additionalContext, true);
    }

    @Override
    public String expand(String inputString, String debugLocation, Map<String, Object> additionalContext, boolean localize) {
        if (inputString == null) return "";
        int inputStringLength = inputString.length();
        if (inputStringLength == 0) return "";

        ExecutionContextImpl eci = (ExecutionContextImpl) null;
        // localize string before expanding
        if (localize && inputStringLength < 256) {
            eci = ecfi.getEci();
            inputString = eci.l10nFacade.localize(inputString);
        }
        // if no $ then it's a plain String, just return it
        if (!inputString.contains("$")) return inputString;

        if (eci == null) eci = ecfi.getEci();
        boolean doPushPop = additionalContext != null && additionalContext.size() > 0;
        ContextStack cs = (ContextStack) null;
        if (doPushPop) cs = eci.contextStack;
        try {
            if (doPushPop) {
                if (additionalContext instanceof EntityValueBase) {
                    cs.push(((EntityValueBase) additionalContext).getValueMap());
                } else {
                    cs.push(additionalContext);
                }
                // do another push so writes to the context don't modify the passed in Map
                cs.push();
            }

            String expression = "\"\"\"" + inputString + "\"\"\"";
            try {
                Script script = getGroovyScript(expression, eci);
                if (script == null) return "";
                Object result = script.run();
                script.setBinding(null);
                return (String) result;
            } catch (Exception e) {
                throw new BaseException("Error in string expression [${expression}] from ${debugLocation}", e);
            }
        } finally {
            if (doPushPop) {
                cs.pop();
                cs.pop();
            }
        }
    }

    public Script getGroovyScript(String expression, ExecutionContextImpl eci) {
        ContextBinding curBinding = eci.contextBindingInternal;

        Map<String, Script> curScriptByExpr = threadScriptByExpression.get();
        if (curScriptByExpr == null) {
            curScriptByExpr = new HashMap<>();
            threadScriptByExpression.set(curScriptByExpr);
        }

        Script script = curScriptByExpr.get(expression);
        if (script == null) {
            script = InvokerHelper.createScript(getGroovyClass(expression), curBinding);
            curScriptByExpr.put(expression, script);
        } else {
            script.setBinding(curBinding);
        }

        return script;
    }

    public Class getGroovyClass(String expression) {
        if (expression == null || expression.isEmpty()) return null;
        Class groovyClass = (Class) scriptGroovyExpressionCache.get(expression);
        if (groovyClass == null) {
            groovyClass = ecfi.compileGroovy(expression, StringUtil.getExpressionClassName(expression));
            scriptGroovyExpressionCache.put(expression, groovyClass);
            // logger.warn("class ${groovyClass.getName()} parsed expression ${expression}")
        }
        return groovyClass;
    }

    @Override
    public String getContentType(String filename) {
        return ResourceReference.getContentType(filename);
    }

    @Override
    public void xslFoTransform(StreamSource xslFoSrc, StreamSource xsltSrc, OutputStream out, String contentType) {
        if (xslFoHandlerFactory == null)
            throw new BaseException("No XSL-FO Handler ToolFactory found (from resource-facade.@xsl-fo-handler-factory)");

        TransformerFactory factory = TransformerFactory.newInstance();
        factory.setURIResolver(new LocalResolver(ecfi, factory.getURIResolver()));

        Transformer transformer = null;
        try {
            transformer = xsltSrc == null ? factory.newTransformer() : factory.newTransformer(xsltSrc);
        } catch (TransformerConfigurationException e) {
            e.printStackTrace();
        }
        if (transformer != null) transformer.setURIResolver(new LocalResolver(ecfi, transformer.getURIResolver()));

        final org.xml.sax.ContentHandler contentHandler = xslFoHandlerFactory.getInstance(out, contentType);

        // There's a ThreadLocal memory leak in XALANJ, reported in 2005 but still not fixed in 2016
        // The memory it prevent GC depend on the fo file size and the thread pool size. So use a separate thread to workaround.
        // https://issues.apache.org/jira/browse/XALANJ-2195
        BaseException transformException = null;
        Transformer finalTransformer = transformer;
        ExecutionContextImpl.ThreadPoolRunnable runnable = new ExecutionContextImpl.ThreadPoolRunnable(ecfi.getEci(), (o) -> {
            try {
                finalTransformer.transform(xslFoSrc, new SAXResult(contentHandler));
            } catch (Throwable t) {
                transformException = new BaseException("Error transforming XSL-FO to " + contentType, t);
            }
        });
        Thread transThread = new Thread(runnable);
        transThread.start();
        try {
            transThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        if (transformException != null) throw transformException;
    }

    public static class LocalResolver implements URIResolver {
        protected ExecutionContextFactoryImpl ecfi;
        protected URIResolver defaultResolver;

        public LocalResolver(ExecutionContextFactoryImpl ecfi, URIResolver defaultResolver) {
            this.ecfi = ecfi;
            this.defaultResolver = defaultResolver;
        }

        public Source resolve(String href, String base) {
            // try plain href
            ResourceReference rr = ecfi.getResource().getLocationReference(href);

            // if href has no colon try base + href
            if (rr == null && href.indexOf(':') < 0) rr = ecfi.getResource().getLocationReference(base + href);

            if (rr != null) {
                URL url = rr.getUrl();
                InputStream is = rr.openStream();
                if (is != null) {
                    if (url != null) {
                        return new StreamSource(is, url.toExternalForm());
                    } else {
                        return new StreamSource(is);
                    }
                }
            }

            try {
                return defaultResolver.resolve(href, base);
            } catch (TransformerException e) {
                e.printStackTrace();
            }
            return null;
        }
    }
}
