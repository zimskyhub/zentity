package com.zmtech.zframework.logger.impl;

import com.zmtech.zframework.context.impl.ExecutionContextFactoryImpl;
import com.zmtech.zframework.logger.LoggerFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class LoggerFacadeImpl implements LoggerFacade {
    protected final static Logger logger = LoggerFactory.getLogger(LoggerFacadeImpl.class);

    protected final ExecutionContextFactoryImpl ecfi;

    public LoggerFacadeImpl(ExecutionContextFactoryImpl ecfi) { this.ecfi = ecfi; }

    public void log(String levelStr, String message, Throwable thrown) {
        int level;
        switch (levelStr) {
            case "trace": level = TRACE_INT; break;
            case "debug": level = DEBUG_INT; break;
            case "info": level = INFO_INT; break;
            case "warn": level = WARN_INT; break;
            case "error": level = ERROR_INT; break;
            case "off": // do nothing
            default: return;
        }
        log(level, message, thrown);
    }

    @Override
    public void log(int level, String message, Throwable thrown) {
        switch (level) {
            case TRACE_INT: logger.trace(message, thrown); break;
            case DEBUG_INT: logger.debug(message, thrown); break;
            case INFO_INT: logger.info(message, thrown); break;
            case WARN_INT: logger.warn(message, thrown); break;
            case ERROR_INT: logger.error(message, thrown); break;
            case FATAL_INT: logger.error(message, thrown); break;
            case ALL_INT: logger.warn(message, thrown); break;
            case OFF_INT: break; // do nothing
        }
    }

    public void trace(String message) { log(TRACE_INT, message, null); }
    public void debug(String message) { log(DEBUG_INT, message, null); }
    public void info(String message) { log(INFO_INT, message, null); }
    public void warn(String message) { log(WARN_INT, message, null); }
    public void error(String message) { log(ERROR_INT, message, null); }

    @Override
    public boolean logEnabled(int level) {
        switch (level) {
            case TRACE_INT: return logger.isTraceEnabled();
            case DEBUG_INT: return logger.isDebugEnabled();
            case INFO_INT: return logger.isInfoEnabled();
            case WARN_INT: return logger.isWarnEnabled();
            case ERROR_INT:
            case FATAL_INT: return logger.isErrorEnabled();
            case ALL_INT: return logger.isWarnEnabled();
            case OFF_INT: return false;
            default: return false;
        }
    }
}
