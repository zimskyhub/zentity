package com.zmtech.zkit.logger;

/**
 * 用于跟踪，错误等记录到控制台，文件等。
 */
public interface LoggerFacade {
    /** 复制 org.apache.logging.log4j.spi.StandardLevel 的日志级别，避免在类路径上加载。 */
    int	OFF_INT = 0;
    int	FATAL_INT = 100;
    int	ERROR_INT = 200;
    int	WARN_INT = 300;
    int	INFO_INT = 400;
    int	DEBUG_INT = 500;
    int	TRACE_INT = 600;
    int	ALL_INT = 2147483647;

    /** 按照不同级别记录抛出的错误信息。
     * 主要适用于脚本，xml-actions等。
     * 在Java或Groovy类中，最好直接使用SLF4J
     * @param level 记录级别。 选项应来自org.apache.log4j.Level.
     * @param message 要记录的消息文本。 如果包含$ {}语法将从当前上下文扩展。
     * @param thrown 可以与堆栈跟踪等一起使用，以便与消息一起记录。
     */
    void log(int level, String message, Throwable thrown);

    void trace(String message);
    void debug(String message);
    void info(String message);
    void warn(String message);
    void error(String message);

    /** 是否启用了给定的日志级别？ */
    boolean logEnabled(int level);
}
