package com.zmtech.zkit.script;


import com.zmtech.zkit.context.ExecutionContext;
import com.zmtech.zkit.context.ExecutionContextFactory;
import com.zmtech.zkit.exception.BaseException;

public interface ScriptRunner {
    ScriptRunner init(ExecutionContextFactory ecf);
    Object run(String location, String method, ExecutionContext ec) throws BaseException;
    void destroy();
}
