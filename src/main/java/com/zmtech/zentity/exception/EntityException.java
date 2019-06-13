package com.zmtech.zentity.exception;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * EntityException
 *
 */
public class EntityException extends RuntimeException {

    public EntityException(String message) { super(message); }
    public EntityException(String message, Throwable nested) { super(message, nested); }
    public EntityException(Throwable nested) { super(nested); }


    @Override public void printStackTrace() { filterStackTrace(this); super.printStackTrace(); }
    @Override public void printStackTrace(PrintStream printStream) { filterStackTrace(this); super.printStackTrace(printStream); }
    @Override public void printStackTrace(PrintWriter printWriter) { filterStackTrace(this); super.printStackTrace(printWriter); }
    @Override public StackTraceElement[] getStackTrace() {
        StackTraceElement[] filteredTrace = filterStackTrace(super.getStackTrace());
        setStackTrace(filteredTrace);
        return filteredTrace;
    }

    public static Throwable filterStackTrace(Throwable t) {
        t.setStackTrace(filterStackTrace(t.getStackTrace()));
        if (t.getCause() != null) filterStackTrace(t.getCause());
        return t;
    }
    public static StackTraceElement[] filterStackTrace(StackTraceElement[] orig) {
        List<StackTraceElement> newList = new ArrayList<>(orig.length);
        for (StackTraceElement ste: orig) {
            String cn = ste.getClassName();
            if (cn.startsWith("freemarker.core.") || cn.startsWith("freemarker.ext.beans.") || cn.startsWith("org.eclipse.jetty.") ||
                    cn.startsWith("java.lang.reflect.") || cn.startsWith("sun.reflect.") ||
                    cn.startsWith("org.codehaus.groovy.") ||  cn.startsWith("groovy.lang.")) {
                continue;
            }
            // if ("renderSingle".equals(ste.getMethodName()) && cn.startsWith("org.moqui.impl.screen.ScreenSection")) continue;
            // if (("internalRender".equals(ste.getMethodName()) || "doActualRender".equals(ste.getMethodName())) && cn.startsWith("org.moqui.impl.screen.ScreenRenderImpl")) continue;
            if (("call".equals(ste.getMethodName()) || "callCurrent".equals(ste.getMethodName())) && ste.getLineNumber() == -1) continue;
            //System.out.println("Adding className: " + cn + ", line: " + ste.getLineNumber());
            newList.add(ste);
        }
        //System.out.println("Called getFilteredStackTrace, orig.length=" + orig.length + ", newList.size()=" + newList.size());
        return newList.toArray(new StackTraceElement[newList.size()]);
    }
}
