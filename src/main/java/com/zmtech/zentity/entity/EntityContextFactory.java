package com.zmtech.zentity.entity;

import com.zmtech.zentity.transaction.TransactionFacade;
import groovy.lang.GroovyClassLoader;

import javax.annotation.Nonnull;
import java.util.LinkedHashMap;
import java.util.List;

public interface EntityContextFactory {
    /** Get the EntityContext associated with the current thread or initialize one and associate it with the thread. */
    @Nonnull
    EntityContext getEntityContext();

    /** Destroy the active Execution Context. When another is requested in this thread a new one will be created. */
    void destroyActiveEntityContext();

    /** Called after construction but before registration with Moqui/Servlet, check for empty database and load configured data. */
    boolean checkEmptyDb();
    /** Destroy this EntityContextFactory and all resources it uses (all facades, tools, etc) */
    void destroy();
    boolean isDestroyed();

    /** Get the path of the runtime directory */
    @Nonnull String getRuntimePath();
    @Nonnull String getZEntityVersion();

//    /** Get the named ToolFactory instance (loaded by configuration) */
//    <V> ToolFactory<V> getToolFactory(@Nonnull String toolName);
//    /** Get an instance object from the named ToolFactory instance (loaded by configuration); the instanceClass may be
//     * null in scripts or other contexts where static typing is not needed */
//    <V> V getTool(@Nonnull String toolName, Class<V> instanceClass, Object... parameters);
//
//    /** Get a Map where each key is a component name and each value is the component's base location. */
//    @Nonnull
//    LinkedHashMap<String, String> getComponentBaseLocations();
//
//    /** For localization (l10n) functionality, like localizing messages. */
//    @Nonnull L10nFacade getL10n();
//
//    /** For accessing resources by location string (http://, jar://, component://, content://, classpath://, etc). */
//    @Nonnull ResourceFacade getResource();
//
//    /** For trace, error, etc logging to the console, files, etc. */
//    @Nonnull LoggerFacade getLogger();
//
//    /** For managing and accessing caches. */
//    @Nonnull CacheFacade getCache();

    /** For transaction operations use this facade instead of the JTA UserTransaction and TransactionManager. See javadoc comments there for examples of code usage. */
    @Nonnull TransactionFacade getTransaction();

    /** For interactions with a relational database. */
    @Nonnull EntityFacade getEntity();

//    /** For calling services (local or remote, sync or async or scheduled). */
//    @Nonnull ServiceFacade getService();
//
//    /** For rendering screens for general use (mostly for things other than web pages or web page snippets). */
//    @Nonnull ScreenFacade getScreen();

    /** Get the framework ClassLoader, aware of all additional classes in runtime and in components. */
    @Nonnull ClassLoader getClassLoader();
    /** Get a GroovyClassLoader for runtime compilation, etc. */
    @Nonnull
    GroovyClassLoader getGroovyClassLoader();

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
