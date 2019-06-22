package com.zmtech.zkit.context;

import com.zmtech.zkit.cache.CacheFacade;
import com.zmtech.zkit.entity.EntityFacade;
import com.zmtech.zkit.l10n.L10nFacade;
import com.zmtech.zkit.logger.LoggerFacade;
import com.zmtech.zkit.resource.ResourceFacade;
import com.zmtech.zkit.tools.ToolFactory;
import com.zmtech.zkit.transaction.TransactionFacade;
import groovy.lang.GroovyClassLoader;

import javax.annotation.Nonnull;

public interface ExecutionContextFactory {
    /** 获取与当前线程关联的ExecutionContext或初始化一个并将其与线程关联。 */
    @Nonnull
    ExecutionContext getExecutionContext();

    /** 销毁活动的执行上下文。 当在该线程中请求另一个时，将创建一个新线程。 */
    void destroyActiveExecutionContext();
    /** 检查空数据库并加载配置数据。 */
    boolean checkEmptyDb();
    /** 销毁ExecutionContextFactory及其使用的所有资源 */
    void destroy();
    /** 判断ExecutionContextFactory是否被销毁 */
    boolean isDestroyed();
    /** 获取运行时目录的路径 */
    @Nonnull String getRuntimePath();
    /** 获取当前ZKit版本 */
    @Nonnull String getZKitVersion();
    /** 获取命名的ToolFactory实例（由配置加载） */
    <V> ToolFactory<V> getToolFactory(@Nonnull String toolName);
    /** 用于管理和访问缓存。 */
    @Nonnull CacheFacade getCache();
    /** 用于本地化（l10n）功能，如本地化消息。 */
    @Nonnull L10nFacade getL10n();
    /** 用于按位置字符串（http：//，jar：//，component：//，content：//，classpath：//等）访问资源。 */
    @Nonnull ResourceFacade getResource();
    /** 用于调用服务（本地或远程，同步或异步）。 */
    @Nonnull ServiceFacade getService();
    /** 用于与关系数据库的交互。 */
    @Nonnull EntityFacade getEntity();
    /** 用于事务管理。对于事务操作，请使用此Facade而不是JTA UserTransaction和TransactionManager。 有关代码用法的示例，请参阅javadoc注释。 */
    @Nonnull TransactionFacade getTransaction();
    /** 用于跟踪，错误等记录到控制台，文件等。 */
    @Nonnull LoggerFacade getLogger();
    /** 获取框架ClassLoader，了解运行时和组件中的所有其他类。 */
    @Nonnull ClassLoader getClassLoader();
    /** 获取GroovyClassLoader以进行运行时编译等。 */
    @Nonnull GroovyClassLoader getGroovyClassLoader();

    /**
     * 从命名的ToolFactory实例获取实例对象（由配置加载）;
     * 在不需要静态类型的脚本或其他上下文中，instanceClass可以为null
     */
    <V> V getTool(@Nonnull String toolName, Class<V> instanceClass, Object... parameters);

//
//    /** Get a Map where each key is a component name and each value is the component's base location. */
//    @Nonnull
//    LinkedHashMap<String, String> getComponentBaseLocations();
//    /** For calling services (local or remote, sync or async or scheduled). */
//    @Nonnull ServiceFacade getService();
//
//    /** For rendering screens for general use (mostly for things other than web pages or web page snippets). */
//    @Nonnull ScreenFacade getScreen();
//    /** The ServletContext, if Moqui was initialized in a webapp (generally through MoquiContextListener) */
//    @Nonnull ServletContext getServletContext();
//    /** The WebSocket ServerContainer, if found in 'javax.websocket.server.ServerContainer' ServletContext attribute */
//    @Nonnull ServerContainer getServerContainer();
//    /** For starting initialization only, tell the ECF about the ServletContext for getServletContext() and getServerContainer() */
//    void initServletContext(ServletContext sc);
//
//    void registerNotificationMessageListener(@Nonnull NotificationMessageListener nml);
//
//    void registerLogEventSubscriber(@Nonnull LogEventSubscriber subscriber);
//    List<LogEventSubscriber> getLogEventSubscribers();
}
