package com.zmtech.zkit.context;


import com.zmtech.zkit.exception.BaseException;

public interface ScriptRunner {
    ScriptRunner init(ExecutionContextFactory ecf);
    Object run(String location, String method, ExecutionContext ec) throws BaseException;
    void destroy();
}
