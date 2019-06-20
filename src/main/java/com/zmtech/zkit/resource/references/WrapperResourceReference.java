package com.zmtech.zkit.resource.references;

import com.zmtech.zkit.context.impl.ExecutionContextFactoryImpl;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.List;


public abstract class WrapperResourceReference extends BaseResourceReference {
    private ResourceReference rr = null;

    public WrapperResourceReference() { }

    @Override
    public ResourceReference init(String location, ExecutionContextFactoryImpl ecf) {
        this.ecf = ecf;
        return this;
    }
    ResourceReference init(ResourceReference rr, ExecutionContextFactoryImpl ecf) {
        this.rr = rr;
        this.ecf = ecf;
        return this;
    }

    @Override public abstract ResourceReference createNew(String location);

    public String getLocation() { return rr.getLocation(); }

    public InputStream openStream() { return rr.openStream(); }
    public OutputStream getOutputStream() { return rr.getOutputStream(); }
    public String getText() { return rr.getText(); }

    public boolean supportsAll() { return rr.supportsAll(); }

    public boolean supportsUrl() { return rr.supportsUrl(); }
    public URL getUrl() { return rr.getUrl(); }

    public boolean supportsDirectory() { return rr.supportsDirectory(); }
    public boolean isFile() { return rr.isFile(); }
    public boolean isDirectory() { return rr.isDirectory(); }
    public List<ResourceReference> getDirectoryEntries() { return rr.getDirectoryEntries(); }

    public boolean supportsExists() { return rr.supportsExists(); }
    public boolean getExists() { return rr.getExists();}

    public boolean supportsLastModified() { return rr.supportsLastModified(); }
    public long getLastModified() { return rr.getLastModified(); }

    public boolean supportsSize() { return rr.supportsSize(); }
    public long getSize() { return rr.getSize(); }

    public boolean supportsWrite() { return rr.supportsWrite(); }
    public void putText(String text) { rr.putText(text); }
    public void putStream(InputStream stream) { rr.putStream(stream); }
    public void move(String newLocation) { rr.move(newLocation); }
    public ResourceReference makeDirectory(String name) { return rr.makeDirectory(name); }
    public ResourceReference makeFile(String name) { return rr.makeFile(name); }
    public boolean delete() { return rr.delete(); }

    public void destroy() { rr.destroy(); }
}
