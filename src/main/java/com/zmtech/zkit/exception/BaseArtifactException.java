package com.zmtech.zkit.exception;

import com.zmtech.zkit.artifact.ArtifactExecutionInfo;
import com.zmtech.zkit.context.ExecutionContextFactory;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.util.Deque;

/** BaseArtifactException  - 扩展BaseException以添加组件堆栈信息。 */
public class BaseArtifactException extends BaseException {
    transient private Deque<ArtifactExecutionInfo> artifactStack = null;

    public BaseArtifactException(String message) { super(message); populateArtifactStack(); }
    public BaseArtifactException(String message, Deque<ArtifactExecutionInfo> curStack) { super(message); artifactStack = curStack; }
    public BaseArtifactException(String message, Throwable nested) { super(message, nested); populateArtifactStack(); }
    public BaseArtifactException(String message, Throwable nested, Deque<ArtifactExecutionInfo> curStack) {
        super(message, nested); artifactStack = curStack; }
    public BaseArtifactException(Throwable nested) { super(nested); populateArtifactStack(); }

    private void populateArtifactStack() {
        ExecutionContextFactory ecf = Moqui.getExecutionContextFactory();
        if (ecf != null) artifactStack = ecf.getExecutionContext().getArtifactExecution().getStack();
    }

    public Deque<ArtifactExecutionInfo> getArtifactStack() { return artifactStack; }

    @Override public void printStackTrace() { printStackTrace(System.err); }
    @Override public void printStackTrace(PrintStream printStream) {
        if (artifactStack != null && artifactStack.size() > 0)
            for (ArtifactExecutionInfo aei : artifactStack) printStream.println(aei.toBasicString());
        filterStackTrace(this);
        super.printStackTrace(printStream);
    }
    @Override public void printStackTrace(PrintWriter printWriter) {
        if (artifactStack != null && artifactStack.size() > 0)
            for (ArtifactExecutionInfo aei : artifactStack) printWriter.println(aei.toBasicString());
        filterStackTrace(this);
        super.printStackTrace(printWriter);
    }
    @Override public StackTraceElement[] getStackTrace() {
        StackTraceElement[] filteredTrace = filterStackTrace(super.getStackTrace());
        setStackTrace(filteredTrace);
        return filteredTrace;
    }
}
