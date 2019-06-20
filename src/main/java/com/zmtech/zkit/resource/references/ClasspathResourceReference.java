package com.zmtech.zkit.resource.references;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClasspathResourceReference extends UrlResourceReference {
    private static final Logger logger = LoggerFactory.getLogger(ClasspathResourceReference.class);
    private String strippedLocation;

    public ClasspathResourceReference() { super(); }

    @Override
    public ResourceReference init(String location) {
        strippedLocation = ResourceReference.stripLocationPrefix(location);
        // 首先尝试当前线程的上下文ClassLoader
        locationUrl = Thread.currentThread().getContextClassLoader().getResource(strippedLocation);
        // 接下来尝试加载此类的ClassLoader
        if (locationUrl == null) locationUrl = this.getClass().getClassLoader().getResource(strippedLocation);
        // no luck? try the system ClassLoader
        if (locationUrl == null) locationUrl = ClassLoader.getSystemResource(strippedLocation);
        // if the URL was found this way then it exists, so remember that
        if (locationUrl != null) {
            exists = true;
            isFileProtocol = "file".equals(locationUrl.getProtocol());
        } else {
            logger.warn("资源类引用警告: 无法在类路径找到 ["+strippedLocation+"]");
        }

        return this;
    }

    @Override
    public ResourceReference createNew(String location) {
        ClasspathResourceReference resRef = new ClasspathResourceReference();
        resRef.init(location);
        return resRef;
    }

    @Override
    public String getLocation() { return "classpath://" + strippedLocation; }
}
