package com.zmtech.zkit.references;

import com.zmtech.zkit.context.impl.ExecutionContextFactoryImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.List;

public abstract class BaseResourceReference extends ResourceReference {
    protected static final Logger logger = LoggerFactory.getLogger(BaseResourceReference.class);
    protected ExecutionContextFactoryImpl ecf = null;

    public BaseResourceReference() { }

    @Override
    public ResourceReference init(String location) { return init(location, null); }
    public abstract ResourceReference init(String location, ExecutionContextFactoryImpl ecf);

    @Override public abstract ResourceReference createNew(String location);
    @Override public abstract String getLocation();
    @Override public abstract InputStream openStream();
    @Override public abstract OutputStream getOutputStream();
    @Override public abstract String getText();

    @Override public abstract boolean supportsAll();
    @Override public abstract boolean supportsUrl();
    @Override public abstract URL getUrl();
    @Override public abstract boolean supportsDirectory();
    @Override public abstract boolean isFile();
    @Override public abstract boolean isDirectory();
    @Override public abstract List<ResourceReference> getDirectoryEntries();

    @Override public abstract boolean supportsExists();
    @Override public abstract boolean getExists();
    @Override public abstract boolean supportsLastModified();
    @Override public abstract long getLastModified();
    @Override public abstract boolean supportsSize();
    @Override public abstract long getSize();

    @Override public abstract boolean supportsWrite();
    @Override public abstract void putText(String text);
    @Override public abstract void putStream(InputStream stream);
    @Override public abstract void move(String newLocation);

    @Override public abstract ResourceReference makeDirectory(String name);
    @Override public abstract ResourceReference makeFile(String name);
    @Override public abstract boolean delete();
}
