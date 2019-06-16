/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 *
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 *
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package com.zmtech.zframework.context;

import com.zmtech.zframework.cache.CacheFacade;
import com.zmtech.zframework.entity.EntityFacade;
import com.zmtech.zframework.l10n.L10nFacade;
import com.zmtech.zframework.resource.ResourceFacade;
import com.zmtech.zframework.transaction.TransactionFacade;
import com.zmtech.zframework.util.ContextBinding;
import com.zmtech.zframework.util.ContextStack;
import groovy.lang.Closure;

import javax.annotation.Nonnull;
import java.util.Map;

/**
 * 整个ZEntity框架中用于管理上下文执行信息和工具接口的对象的接口定义。
 * 对于运行代码的每个线程，将存在此对象的一个实例，并且仅适用于该线程。
 */
@SuppressWarnings("unused")
public interface ExecutionContext {
    /** 取ExecutionContextFactory。. */
    @Nonnull
    ExecutionContextFactory getFactory();

    /**
     * 返回一个Map，
     * 表示正在运行的任何内容中的当前局部变量（context）。
     */
    @Nonnull
    ContextStack getContext();
    @Nonnull
    ContextBinding getContextBinding();
    /** Returns a Map that represents the global/root variable space (context), ie the bottom of the context stack. */
    @Nonnull
    Map<String, Object> getContextRoot();
    /** For localization (l10n) functionality, like localizing messages. */
    @Nonnull
    L10nFacade getL10n();
    /** For accessing resources by location string (http://, jar://, component://, content://, classpath://, etc). */
    @Nonnull
    ResourceFacade getResource();
    /** For managing and accessing caches. */
    @Nonnull
    CacheFacade getCache();
    /** For transaction operations use this facade instead of the JTA UserTransaction and TransactionManager. See javadoc comments there for examples of code usage. */
    @Nonnull
    TransactionFacade getTransaction();
    /** For interactions with a relational database. */
    @Nonnull
    EntityFacade getEntity();


    /** Get an instance object from the named ToolFactory instance (loaded by configuration). Some tools return a
     * singleton instance, others a new instance each time it is used and that instance is saved with this
     * ExecutionContext to be reused. The instanceClass may be null in scripts or other contexts where static typing
     * is not needed */
    <V> V getTool(@Nonnull String toolName, Class<V> instanceClass, Object... parameters);

//    /** If running through a web (HTTP servlet) request offers access to the various web objects/information.
//     * If not running in a web context will return null.
//     */
//    @Nullable
//    WebFacade getWeb();
//
//    /** For information about the user and user preferences (including locale, time zone, currency, etc). */
//    @Nonnull
//    UserFacade getUser();
//
//    /** For user messages including general feedback, errors, and field-specific validation errors. */
//    @Nonnull
//    MessageFacade getMessage();
//    /** For information about artifacts as they are being executed. */
//    @Nonnull
//    ArtifactExecutionFacade getArtifactExecution();
//
//
//    /** For trace, error, etc logging to the console, files, etc. */
//    @Nonnull
//    LoggerFacade getLogger();
//

    /** A lightweight asynchronous executor. An alternative to Quartz, still ExecutionContext aware and uses
     * the current ExecutionContext in the separate thread (retaining user, authz context, etc). */
    void runAsync(@Nonnull Closure closure);

    /** This should be called when the ExecutionContext won't be used any more. Implementations should make sure
     * any active transactions, database connections, etc are closed.
     */
    void destroy();
}
