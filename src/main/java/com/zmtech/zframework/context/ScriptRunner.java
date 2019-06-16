package com.zmtech.zframework.context;


import com.zmtech.zframework.exception.BaseException;

public interface ScriptRunner {
    ScriptRunner init(ExecutionContextFactory ecf);
    Object run(String location, String method, ExecutionContext ec) throws BaseException;
    void destroy();
}
