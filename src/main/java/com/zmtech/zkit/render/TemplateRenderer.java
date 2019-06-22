package com.zmtech.zkit.render;


import com.zmtech.zkit.context.ExecutionContextFactory;
import com.zmtech.zkit.exception.BaseException;
import java.io.Writer;

public interface TemplateRenderer {
    TemplateRenderer init(ExecutionContextFactory ecf);
    void render(String location, Writer writer) throws BaseException;
    String stripTemplateExtension(String fileName);
    void destroy();
}
