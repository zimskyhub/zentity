package com.zmtech.zkit.resource.impl;

import com.zmtech.zkit.cache.impl.ZCache;
import com.zmtech.zkit.context.ScriptRunner;
import com.zmtech.zkit.context.TemplateRenderer;
import com.zmtech.zkit.context.impl.ExecutionContextFactoryImpl;
import com.zmtech.zkit.context.impl.ExecutionContextImpl;
import com.zmtech.zkit.context.renderer.FtlTemplateRenderer;
import com.zmtech.zkit.context.runner.JavaxScriptRunner;
import com.zmtech.zkit.context.runner.XmlActionsScriptRunner;
import com.zmtech.zkit.entity.impl.EntityValueBase;
import com.zmtech.zkit.exception.BaseException;
import com.zmtech.zkit.resource.ResourceFacade;
import com.zmtech.zkit.tools.ToolFactory;
import com.zmtech.zkit.util.*;
import groovy.lang.Closure;
import groovy.lang.Script;
import org.codehaus.groovy.runtime.InvokerHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private final FtlTemplateRenderer ftlTemplateRenderer;
    private final XmlActionsScriptRunner xmlActionsScriptRunner;

    // the groovy Script object is not thread safe, so have one per thread per expression; can be reused as thread is reused
    private final ThreadLocal<Map<String, Script>> threadScriptByExpression = new ThreadLocal<>();
    private final Map<String, Class> scriptGroovyExpressionCache = new HashMap<>();

    private final Cache<String, String> textLocationCache;
    private final Cache<String, ResourceReference> resourceReferenceByLocation;

    private final Map<String, Class> resourceReferenceClasses = new HashMap<>();
    private final Map<String, TemplateRenderer> templateRenderers = new HashMap<>();
    private final ArrayList<String> templateRendererExtensions = new ArrayList<>();
    private final ArrayList<Integer> templateRendererExtensionsDots = new ArrayList<>();
    private final Map<String, ScriptRunner> scriptRunners = new HashMap<>();
    private final ScriptEngineManager scriptEngineManager = new ScriptEngineManager();
    private final ToolFactory<org.xml.sax.ContentHandler> xslFoHandlerFactory;

    private final Map<String, Repository> contentRepositories = new HashMap<>();
    private final ThreadLocal<Map<String, Session>> contentSessions = new ThreadLocal<>();

    public ResourceFacadeImpl(ExecutionContextFactoryImpl ecfi) {
        this.ecfi = ecfi;

        ftlTemplateRenderer = new FtlTemplateRenderer();
        ftlTemplateRenderer.init(ecfi);

        xmlActionsScriptRunner = new XmlActionsScriptRunner();
        xmlActionsScriptRunner.init(ecfi);

        textLocationCache = ecfi.getCache().getCache("resource.text.location", String.class, String.class);
        // 使用HashMap在这里更快：scriptGroovyExpressionCache = ecfi.cacheFacade.getCache（“resource.groovy.expression”）
        resourceReferenceByLocation = ecfi.getCache().getCache("resource.reference.location", String.class, ResourceReference.class);

        MNode resourceFacadeNode = ecfi.getConfXmlRoot().first("resource-facade");

        // 设置资源引用类
        for (MNode rrNode : resourceFacadeNode.children("resource-reference")) {
            try {
                Class rrClass = Thread.currentThread().getContextClassLoader().loadClass(rrNode.attribute("class"));
                resourceReferenceClasses.put(rrNode.attribute("scheme"), rrClass);
            } catch (ClassNotFoundException e) {
                logger.info("资源操作信息: 类 [" + rrNode.attribute("class") + "] 不存在 (" + e.toString() + ")");
            }
        }

        // 设置模板渲染器
        for (MNode templateRendererNode : resourceFacadeNode.children("template-renderer")) {
            TemplateRenderer tr = null;
            try {
                tr = (TemplateRenderer) Thread.currentThread().getContextClassLoader()
                        .loadClass(templateRendererNode.attribute("class")).newInstance();
            } catch (Exception e) {
                logger.error("资源操作错误: 模板渲染类 [" + templateRendererNode.attribute("class") + "] 不存在或无法初始化:" + e.toString());
            }
            templateRenderers.put(templateRendererNode.attribute("extension"), tr.init(ecfi));
        }
        for (String ext : templateRenderers.keySet()) {
            templateRendererExtensions.add(ext);
            templateRendererExtensionsDots.add(ObjectUtil.countChars(ext, (char) '.'));
        }

        // 设置脚本运行程序
        for (MNode scriptRunnerNode : resourceFacadeNode.children("script-runner")) {
            if (scriptRunnerNode.attribute("class") != null) {
                ScriptRunner sr = null;
                try {
                    sr = (ScriptRunner) Thread.currentThread().getContextClassLoader()
                            .loadClass(scriptRunnerNode.attribute("class")).newInstance();
                } catch (Exception e) {
                    logger.error("资源操作错误: 脚本运行类 [" + scriptRunnerNode.attribute("class") + "] 不存在或无法初始化。" + e.toString());
                }
                scriptRunners.put(scriptRunnerNode.attribute("extension"), sr.init(ecfi));
            } else if (scriptRunnerNode.attribute("engine") != null) {
                ScriptRunner sr = new JavaxScriptRunner(scriptRunnerNode.attribute("engine")).init(ecfi);
                scriptRunners.put(scriptRunnerNode.attribute("extension"), sr);
            } else {
                logger.error("资源操作错误: 配置扩展 [" + scriptRunnerNode.attribute("extension") + "] 脚本运行程序必须具有类或引擎属性。");
            }
        }

        // 获得 XSL-FO 处理器
        if (resourceFacadeNode.attribute("xsl-fo-handler-factory") != null) {
            xslFoHandlerFactory = ecfi.getToolFactory(resourceFacadeNode.attribute("xsl-fo-handler-factory"));
            if (xslFoHandlerFactory != null) {
                logger.info("资源操作信息: 正在使用 xsl-fo-handler-factory [" + resourceFacadeNode.attribute("xsl-fo-handler-factory") + "] (" + xslFoHandlerFactory.getClass().getName() + ")");
            } else {
                logger.warn("资源操作警告: 无法找到名为 [" + resourceFacadeNode.attribute("xsl-fo-handler-factory") + "] 的 xsl-fo-handler-factory!");
            }
        } else {
            xslFoHandlerFactory = null;
        }

        // 设置内容仓库
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
                    logger.info("资源操作信息: 添加了工作区为 [" + repositoryNode.attribute("workspace") + "] 名为 [" + repoName + "] 类型为 [" + repo.getClass().getName() + "] 参数为 [" + parameters + "] 的 JCR 仓库");
                } else {
                    logger.error("资源操作错误: 无法找到名为 [" + repoName + "] 参数为 [" + parameters + "] 的 JCR 资源仓库!");
                }
            } catch (Exception e) {
                logger.error("资源操作错误: 无法获取名为 [" + repositoryNode.attribute("name") + "] 的 JCR 资源仓库 :" + e.toString());
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
     * 获取上下文/线程中的活动 JCR Session，确保它是活动的，并在需要时创建一个。
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
                .first(new Closure<Boolean>(this) {
                    @Override
                    public Boolean call(Object arg) {
                        MNode it = (MNode) arg;
                        return it.getName().equals("repository") && it.attribute("name").equals(name);
                    }
                });
        SimpleCredentials credentials = new SimpleCredentials(repositoryNode.attribute("username") != null ? repositoryNode.attribute("username") : "anonymous",
                (repositoryNode.attribute("password") != null ? repositoryNode.attribute("password") : "").toCharArray());
        if (repositoryNode.attribute("workspace") != null) {
            try {
                newSession = rep.login(credentials, repositoryNode.attribute("workspace"));
            } catch (RepositoryException e) {
                logger.error("资源操作错误: 无法登录工作区名为 [" + repositoryNode.attribute("workspace") + "] 的 JCR 资源仓库 :" + e.toString());
            }
        } else {
            try {
                newSession = rep.login(credentials);
            } catch (RepositoryException e) {
                logger.error("资源操作错误: 无法登录工作区名为 [" + repositoryNode.attribute("workspace") + "] 的 JCR 资源仓库 :" + e.toString());
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
            throw new BaseException("资源操作错误: 地址 [" + location + "] 不支持前缀 [" + scheme + "]!");

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
        // Q: 如何获得Windows的方案？ Java URI类不喜欢空格，如果我们查找第一个“：”它可能是驱动器号而不是方案/协议
        // A: 如果前面只有一个字符，则忽略冒号
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
            logger.info("资源操作信息: 无法从地址 [" + location + "] 获取资源的引用,返回空地址!");
            return "";
        }
        // 按版本获取时不要缓存
        if (versionName != null) cache = false;
        if (cache) {
            String cachedText;
            if (textLocationCache instanceof ZCache) {
                ZCache<String, String> mCache = (ZCache<String, String>) textLocationCache;
                // 如果我们有一个资源引用并且最后修改了比缓存条目更新然后将其抛出（当缓存条目更新时间更早/小于rr.lastModified时到期）
                cachedText = mCache.get(location, textRr.getLastModified());
            } else {
                // TODO: 没有缓存到期/清除时不支持动态重新加载！
                cachedText = textLocationCache.get(location);
            }
            if (cachedText != null) return cachedText;
        }
        InputStream locStream = textRr.openStream(versionName);
        if (locStream == null) logger.info("资源操作信息: 地址 [" + location + "] 下没有任何资源或文本!");
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
        // 剥离模板扩展，避免尝试查找内容类型时出现问题
        String fileContentType = getContentType(tr != null ? tr.stripTemplateExtension(fileName) : fileName);

        boolean isBinary = ResourceReference.isBinaryContentType(fileContentType);

        if (isBinary) {
            try {
                return new ByteArrayDataSource(fileResourceRef.openStream(versionName), fileContentType);
            } catch (IOException e) {
                logger.error("资源操作错误: 无法加载地址 [" + location + "] 的资源文件:" + e.toString());
            }
        } else {
            // 不是二进制对象（希望如此），获取文本并将其传递过来
            if (tr != null) {
                // 注意：此处忽略版本
                StringWriter sw = new StringWriter();
                tr.render(fileResourceRef.getLocation(), sw);
                try {
                    return new ByteArrayDataSource(sw.toString(), fileContentType);
                } catch (IOException e) {
                    logger.error("资源操作错误: 无法加载地址 [" + fileResourceRef.getLocation() + "] 的资源文件:" + e.toString());
                }
            } else {
                // 找不到渲染器，只需抓取文本（缓存）并将其抛给作者
                String textLoc = fileResourceRef.getLocation();
                if (versionName != null && !versionName.isEmpty()) textLoc = textLoc.concat("#").concat(versionName);
                String text = getLocationText(textLoc, true);
                try {
                    return new ByteArrayDataSource(text, fileContentType);
                } catch (IOException e) {
                    logger.error("资源操作错误: 无法加载文本 [" + text + "] 为资源文件:" + e.toString());
                }
            }
        }

        return null;
    }

    @Override
    public void template(String location, Writer writer) {
        // 注意：让版本通过tr.render（）和getLocationText（）
        TemplateRenderer tr = getTemplateRendererByLocation(location);
        if (tr != null) {
            tr.render(location, writer);
        } else {
            // 找不到渲染器，只需抓取文本并将其扔给作者
            String text = getLocationText(location, true);
            if (text != null && !text.isEmpty()) {
                try {
                    writer.write(text);
                } catch (IOException e) {
                    logger.error("资源操作错误: 无法写入地址 [" + location + "] 的资源文件:" + e.toString());
                }
            }
        }
    }

    public static final Set<String> binaryExtensions = new HashSet<>(Arrays.asList("png", "jpg", "jpeg", "gif", "pdf", "doc", "docx", "xsl", "xslx"));

    public TemplateRenderer getTemplateRendererByLocation(String location) {
        int hashIdx = location.indexOf("#");
        if (hashIdx > 0) location = location.substring(0, hashIdx);

        // 匹配模板渲染器的扩展名，尽可能多的点匹配（最具体的匹配）
        int lastSlashIndex = location.lastIndexOf("/");
        int dotIndex = location.indexOf(".", lastSlashIndex);
        String fullExt = location.substring(dotIndex + 1);
        TemplateRenderer tr = templateRenderers.get(fullExt);
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
            String ext = templateRendererExtensions.get(i);
            if (location.endsWith(ext)) {
                int dots = templateRendererExtensionsDots.get(i).intValue();
                if (dots > mostDots) {
                    mostDots = dots;
                    tr = templateRenderers.get(ext);
                }
            }
        }
        // 如果没有扩展模板渲染器，请记住
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
        // 注意：此处忽略版本

        ExecutionContextImpl ec = ecfi.getEci();
        String extension = location.substring(location.lastIndexOf("."));
        ScriptRunner sr = scriptRunners.get(extension);

        if (sr != null) {
            return sr.run(location, method, ec);
        } else {
            // 看看扩展名是否已知
            ScriptEngine engine = scriptEngineManager.getEngineByExtension(extension);
            if (engine == null)
                throw new BaseException("资源操作错误: 无法运行地址为 [" + location + "] 的脚本, 扩展名不为,配置中的文件扩展名,并且 Java 脚本运行管理器无法识别!");
            return JavaxScriptRunner.bindAndRun(location, ec, engine, ecfi.getCache().getCache("resource.script" + extension + ".location"));
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
                // 再做一次推送，写入上下文不会修改Map中传递的内容
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
            throw new BaseException("资源操作草屋: 在 [" + debugLocation + "] 中的条件表达式 [" + expression + "] 存在错误!", e);
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
                // 再做一次推送，写入上下文不会修改Map中传递的内容
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

    public FtlTemplateRenderer getFtlTemplateRenderer() {
        return ftlTemplateRenderer;
    }

    public XmlActionsScriptRunner getXmlActionsScriptRunner() {
        return xmlActionsScriptRunner;
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
            throw new BaseException("资源操作错误: 在 [" + debugLocation + "] 中的字段表达式 [" + expression + "] 存在错误!", e);
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
                // 再做一次推送，写入上下文不会修改Map中传递的内容
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

        ExecutionContextImpl eci = null;
        // 在扩展之前本地化字符串
        if (localize && inputStringLength < 256) {
            eci = ecfi.getEci();
            inputString = eci.getL10n().localize(inputString);
        }
        // 如果没有$那么它是一个普通的字符串，只需返回它
        if (!inputString.contains("$")) return inputString;

        if (eci == null) eci = ecfi.getEci();
        boolean doPushPop = additionalContext != null && additionalContext.size() > 0;
        ContextStack cs = null;
        if (doPushPop) cs = eci.contextStack;
        try {
            if (doPushPop) {
                if (additionalContext instanceof EntityValueBase) {
                    cs.push(((EntityValueBase) additionalContext).getValueMap());
                } else {
                    cs.push(additionalContext);
                }
                // 再做一次推送，写入上下文不会修改Map中传递的内容
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
                throw new BaseException("资源操作错误: 在 [" + debugLocation + "] 的文本表达式 [" + expression + "] 错误!", e);
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
        Class groovyClass = scriptGroovyExpressionCache.get(expression);
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
            throw new BaseException("资源操作错误:resource-facade.@xsl-fo-handler-factory 中没有找到 XSL-FO Handler ToolFactory");

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

        // XALANJ中存在ThreadLocal内存泄漏，在2005年报告但在2016年仍未修复。
        // 它阻止GC的内存取决于文件大小和线程池大小。 因此，请使用单独的线程来解决此问题。
        // https://issues.apache.org/jira/browse/XALANJ-2195
        final BaseException[] transformException = {null};
        Transformer finalTransformer = transformer;
        ExecutionContextImpl.ThreadPoolRunnable runnable = new ExecutionContextImpl.ThreadPoolRunnable(ecfi.getEci(), new Closure(this) {
            @Override
            public Object call() {
                try {
                    finalTransformer.transform(xslFoSrc, new SAXResult(contentHandler));
                } catch (Throwable t) {
                    transformException[0] = new BaseException("资源操作错误: 无法转换 XSL-FO 为 [" + contentType + "]:", t);
                }
                return true;
            }
        });
        Thread transThread = new Thread(runnable);
        transThread.start();
        try {
            transThread.join();
        } catch (InterruptedException e) {
            throw new BaseException("资源操作错误: 转换 XSL-FO 线程中断!", e);
        }
        if (transformException[0] != null) throw transformException[0];
    }

    public static class LocalResolver implements URIResolver {
        protected ExecutionContextFactoryImpl ecfi;
        protected URIResolver defaultResolver;

        public LocalResolver(ExecutionContextFactoryImpl ecfi, URIResolver defaultResolver) {
            this.ecfi = ecfi;
            this.defaultResolver = defaultResolver;
        }

        public Source resolve(String href, String base) {
            // 试试普通的href
            ResourceReference rr = ecfi.getResource().getLocationReference(href);

            // 如果href没有冒号尝试base + href
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
