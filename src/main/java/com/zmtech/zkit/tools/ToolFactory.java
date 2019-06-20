package com.zmtech.zkit.tools;

import com.zmtech.zkit.context.ExecutionContextFactory;

/**
 * 实现此接口以管理使用ZKit Framework初始化和销毁的工具的生命周期和工厂。
 * 实现必须具有公共无参数构造函数。
 */
public interface ToolFactory<V> {
    /**
     * 通过ExecutionContextFactory.getToolFactory（）返回工厂将可用的名称
     * 方法和实例将通过ExecutionContextFactory.getTool（）方法提供。
     */
    default String getName() {
        String className = this.getClass().getSimpleName();
        int tfIndex = className.indexOf("ToolFactory");
        if (tfIndex > 0) className = className.substring(0, tfIndex);
        return className;
    }

    /**
     * 初始化基础工具，如果实例是单例也是实例.
     */
    default void init(ExecutionContextFactory ecf) { }

    /**
     * 很少在Zkit Facades初始化之前进行初始化时使用;
     * 适用于ResourceReference，ScriptRunner，TemplateRenderer，ServiceRunner等实现所依赖的工具。
     */
    default void preFacadeInit(ExecutionContextFactory ecf) { }

    /**
     * 由ExecutionContextFactory.getTool（）调用以获取此工具的实例对象。
     * 可以为每个呼叫或是单例。
     * @throws IllegalStateException 如果没有初始化
     */
    V getInstance(Object... parameters);

    /** 调用Zkit的销毁/关闭来销毁（关闭，关闭等）底层工具。 */
    default void destroy() { }

    /** 很少使用，像destroy（），但在facade被摧毁后运行。 */
    default void postFacadeDestroy() { }
}
