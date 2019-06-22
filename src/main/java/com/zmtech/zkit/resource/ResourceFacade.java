package com.zmtech.zkit.resource;

import com.zmtech.zkit.render.impl.FtlTemplateRenderer;
import com.zmtech.zkit.script.runner.XmlActionsScriptRunner;
import com.zmtech.zkit.references.ResourceReference;

import javax.activation.DataSource;
import javax.xml.transform.stream.StreamSource;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.util.Map;

/** 用于按位置字符串（http：//，jar：//，component：//，content：//，classpath：//等）访问资源。 */
public interface ResourceFacade {
    /**
     * 获取表示传递的zkit位置字符串的ResourceReference。
     * @param location URL样式的位置字符串。
     *                 除了标准URL协议（http，https，ftp，jar和file）之外，
     *                 还可以为相对于组件基本位置的资源位置具有“component：//”的特殊Moqui协议，“content：//” 对于内容存储库中的资源，
     *                 使用“classpath：//”从Java类路径获取资源。
     */
    ResourceReference getLocationReference(String location);

    /** 打开InputStream以读取某个位置的文件/文档的内容。
     *
     * @param location URL的位置字符串，也支持Zkit特定的组件和内容协议。
     */
    InputStream getLocationStream(String location);

    /** 获取给定位置的文本，可选地从缓存（resource.text.location）获取。 */
    String getLocationText(String location, boolean cache);
    DataSource getLocationDataSource(String location);

    /** 使用当前上下文在给定位置渲染模板，并将输出写入给定的编写器。 */
    void template(String location, Writer writer);

    /**
     * 使用当前上下文为其变量空间在给定位置（可选地使用给定方法，如在groovy类中）运行脚本。
     * @return 脚本返回的值（如果有）。
     */
    Object script(String location, String method);
    Object script(String location, String method, Map<String,Object> additionalContext);

    /**
     * 将Groovy表达式转换为Condition。
     * @return boolean 运行表达式的结果
     */
    boolean condition(String expression, String debugLocation);
    boolean condition(String expression, String debugLocation, Map<String,Object> additionalContext);

    /**
     * 计算Groovy表达式的上下文，或者更一般地将其作为计算为Object引用的表达式。
     * 可用于从表达式获取值或运行任何常规表达式或脚本。
     * @return 运行表达式的结果
     */
    Object expression(String expr, String debugLocation);
    Object expression(String expr, String debugLocation, Map<String,Object> additionalContext);

    /**
     * 将Groovy表达式运行结果扩展/插入到简单字符串中的GString。
     * 注意：在运行表达式之前，inputString始终通过L10nFacade.localize（）方法运行，以隐式地国际化字符串扩展。
     * @return 运行表达式的结果
     */
    String expand(String inputString, String debugLocation);
    String expand(String inputString, String debugLocation, Map<String,Object> additionalContext);
    String expand(String inputString, String debugLocation, Map<String,Object> additionalContext, boolean localize);
    String expandNoL10n(String inputString, String debugLocation);

    FtlTemplateRenderer getFtlTemplateRenderer();
    XmlActionsScriptRunner getXmlActionsScriptRunner();
    void xslFoTransform(StreamSource xslFoSrc, StreamSource xsltSrc, OutputStream out, String contentType);

    String getContentType(String filename);
    void destroyAllInThread();
}
