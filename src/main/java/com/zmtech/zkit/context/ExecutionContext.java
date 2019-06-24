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
package com.zmtech.zkit.context;

import com.zmtech.zkit.cache.CacheFacade;
import com.zmtech.zkit.entity.EntityFacade;
import com.zmtech.zkit.l10n.L10nFacade;
import com.zmtech.zkit.logger.LoggerFacade;
import com.zmtech.zkit.message.MessageFacade;
import com.zmtech.zkit.resource.ResourceFacade;
import com.zmtech.zkit.transaction.TransactionFacade;
import com.zmtech.zkit.util.ContextBinding;
import com.zmtech.zkit.util.ContextStack;
import groovy.lang.Closure;

import javax.annotation.Nonnull;
import java.sql.Timestamp;
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
    /** 返回表示全局/根变量空间（上下文）的Map，即上下文根堆栈。 */
    @Nonnull
    Map<String, Object> getContextRoot();
    /** 用于本地化（l10n）功能，如本地化。 */
    @Nonnull
    L10nFacade getL10n();
    /** 用于按位置字符串（http：//，jar：//，component：//，content：//，classpath：//等）访问资源。 */
    @Nonnull
    ResourceFacade getResource();
    /** 用于管理和访问缓存。 */
    @Nonnull
    CacheFacade getCache();
    /** 对于事务操作，请使用此Facade而不是JTA UserTransaction和TransactionManager。 有关代码用法的示例，请参阅javadoc注释。 */
    @Nonnull
    TransactionFacade getTransaction();
    /** 用于与关系数据库的交互。 */
    @Nonnull
    EntityFacade getEntity();
    /** 对于用户消息，包括一般反馈，错误和特定于字段的验证错误。 */
    @Nonnull
    MessageFacade getMessage();
    /** 用于日志的跟踪，错误等，文件等。 */
    @Nonnull LoggerFacade getLogger();


    /**
     * 从命名的ToolFactory实例获取实例对象（由配置加载）。
     * 有些工具返回单例实例，其他工具每次使用时返回一个新实例，
     * 并且该实例与此ExecutionContext一起保存以供重用。
     * 在不需要静态类型的脚本或其他上下文中，instanceClass可以为null
     */
    <V> V getTool(@Nonnull String toolName, Class<V> instanceClass, Object... parameters);

    /**
     * 轻量级异步执行程序。
     * Quartz的替代方法，仍然可以识别ExecutionContext并在单独的线程中使用当前的ExecutionContext（保留用户，authz上下文等）。
     */
    void runAsync(@Nonnull Closure closure);

    /**
     * 当不再使用ExecutionContext时，应调用此方法。
     * 实现应确保关闭任何活动事务，数据库连接等。
     */
    void destroy();

    Timestamp getNowTimestamp();

    void setEffectiveTime(Timestamp effectiveTime);
}
