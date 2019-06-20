package com.zmtech.zkit.references;

import com.zmtech.zkit.exception.BaseException;
import com.zmtech.zkit.util.ObjectUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

public class UrlResourceReference extends ResourceReference {
    private static final Logger logger = LoggerFactory.getLogger(UrlResourceReference.class);
    URL locationUrl = null;
    Boolean exists = null;
    boolean isFileProtocol = false;
    private transient File localFile = null;

    public UrlResourceReference() {
    }

    public UrlResourceReference(File file) {
        isFileProtocol = true;
        localFile = file;
        try {
            locationUrl = file.toURI().toURL();
        } catch (MalformedURLException e) {
            throw new BaseException("URL资源错误: 无法为文件 [" + file.getAbsolutePath() + "] 创建URL!", e);
        }
    }

    @Override
    public ResourceReference init(String location) {
        if (location == null || location.isEmpty()) throw new BaseException("URL资源错误: 无法为地址为空的资源引用创建URL");

        if (location.startsWith("/") || !location.contains(":")) {
            // 没有前缀，本地文件：如果以'/'开头是绝对的，否则是相对于运行时路径
            if (location.charAt(0) != '/') {
                String zkitRuntime = System.getProperty("zkit.runtime");
                if (zkitRuntime != null && !zkitRuntime.isEmpty()) {
                    File runtimeFile = new File(zkitRuntime);
                    location = runtimeFile.getAbsolutePath() + "/" + location;
                }
            }

            try {
                locationUrl = new URL("file:" + location);
            } catch (MalformedURLException e) {
                throw new BaseException("URL资源错误: 文件地址 [" + location + "] URL无效", e);
            }
            isFileProtocol = true;
        } else {
            try {
                locationUrl = new URL(location);
            } catch (MalformedURLException e) {
                if (logger.isTraceEnabled())
                    logger.trace("URL资源跟踪: 忽略位置格式错误的URL异常, 尝试本地文件...: " + e.toString());
                // special case for Windows, try going through a file:

                try {
                    locationUrl = new URL("file:/" + location);
                } catch (MalformedURLException se) {
                    throw new BaseException("URL资源错误: 文件地址 [" + location + "] URL无效", e);
                }
            }

            isFileProtocol = "file".equals(getUrl().getProtocol());
        }

        return this;
    }

    public File getFile() {
        if (!isFileProtocol)
            throw new IllegalArgumentException("URL资源错误: 文件不支持资源协议 [" + locationUrl.getProtocol() + "]");
        if (localFile != null) return localFile;
        // 注意：使用toExternalForm（）。substring（5）而不是toURI，因为URI不允许文件名中的空格
        localFile = new File(locationUrl.toExternalForm().substring(5));
        return localFile;
    }

    @Override
    public ResourceReference createNew(String location) {
        UrlResourceReference resRef = new UrlResourceReference();
        resRef.init(location);
        return resRef;
    }

    @Override
    public String getLocation() {
        return locationUrl.toString();
    }

    @Override
    public InputStream openStream() {
        try {
            return locationUrl.openStream();
        } catch (FileNotFoundException e) {
            return null;
        } catch (IOException e) {
            throw new BaseException("URL资源错误: 无法打开地址 [" + locationUrl.toString() + "] 的输入流!", e);
        }
    }

    @Override
    public OutputStream getOutputStream() {
        if (!isFileProtocol) {
            final URL url = locationUrl;
            throw new IllegalArgumentException("URL资源错误: 带有协议 [" + url.getProtocol() + "] 的资源 [" + url.toString() + "] 不支持写入!");
        }

        // 首先确保该目录存在
        File curFile = getFile();
        if (!curFile.getParentFile().exists()) curFile.getParentFile().mkdirs();
        try {
            return new FileOutputStream(curFile);
        } catch (FileNotFoundException e) {
            throw new BaseException("URL资源错误: 无法打开文件 [" + curFile.getAbsolutePath() + "] 的输出流!", e);
        }
    }

    @Override
    public String getText() {
        return ObjectUtil.getStreamText(openStream());
    }

    @Override
    public boolean supportsAll() {
        return isFileProtocol;
    }

    @Override
    public boolean supportsUrl() {
        return true;
    }

    @Override
    public URL getUrl() {
        return locationUrl;
    }

    @Override
    public boolean supportsDirectory() {
        return isFileProtocol;
    }

    @Override
    public boolean isFile() {
        if (isFileProtocol) {
            return getFile().isFile();
        } else {
            throw new IllegalArgumentException("URL资源错误: 资源文件不支持协议 [" + locationUrl.getProtocol() + "]");
        }
    }

    @Override
    public boolean isDirectory() {
        if (isFileProtocol) {
            return getFile().isDirectory();
        } else {
            throw new IllegalArgumentException("URL资源错误: 资源文件夹不支持协议 [" + locationUrl.getProtocol() + "]");
        }
    }

    @Override
    public List<ResourceReference> getDirectoryEntries() {
        if (isFileProtocol) {
            File f = getFile();
            List<ResourceReference> children = new ArrayList<>();
            String baseLocation = getLocation();
            if (baseLocation.endsWith("/")) baseLocation = baseLocation.substring(0, baseLocation.length() - 1);
            File[] listFiles = f.listFiles();
            TreeSet<String> fileNameSet = new TreeSet<>();
            if (listFiles != null) for (File dirFile : listFiles) fileNameSet.add(dirFile.getName());
            for (String filename : fileNameSet)
                children.add(new UrlResourceReference().init(baseLocation + "/" + filename));
            return children;
        } else {
            throw new IllegalArgumentException("URL资源错误: 资源子文件夹不支持协议 [" + locationUrl.getProtocol() + "]");
        }
    }

    @Override
    public boolean supportsExists() {
        return isFileProtocol || exists != null;
    }

    @Override
    public boolean getExists() {
        // 如果为true，则只计数存在
        if (exists != null && exists) return true;

        if (isFileProtocol) {
            exists = getFile().exists();
            return exists;
        } else {
            final URL url = locationUrl;
            throw new IllegalArgumentException("URL资源错误: Exists 不支持协议 [" + (url == null ? null : url.getProtocol()) + "]");
        }
    }

    @Override
    public boolean supportsLastModified() {
        return isFileProtocol;
    }

    @Override
    public long getLastModified() {
        if (isFileProtocol) {
            return getFile().lastModified();
        } else {
            return System.currentTimeMillis();
        }
    }

    @Override
    public boolean supportsSize() {
        return isFileProtocol;
    }

    @Override
    public long getSize() {
        return isFileProtocol ? getFile().length() : 0;
    }

    @Override
    public boolean supportsWrite() {
        return isFileProtocol;
    }

    @Override
    public void putText(String text) {
        if (!isFileProtocol) {
            final URL url = locationUrl;
            throw new IllegalArgumentException("URL资源错误: 地址 [" + getLocation() + "] 协议为 [" + (url == null ? null : getUrl().getProtocol()) + "] 的资源不支持写入!");
        }

        // 首先确保该目录存在
        File curFile = getFile();
        if (!curFile.getParentFile().exists()) curFile.getParentFile().mkdirs();
        // 现在将文本写入文件并关闭它
        try {
            Writer fw = new OutputStreamWriter(new FileOutputStream(curFile), StandardCharsets.UTF_8);
            fw.write(text);
            fw.close();
            this.exists = null;
        } catch (IOException e) {
            throw new BaseException("URL资源错误: 文件 [" + curFile.getAbsolutePath() + "] 无法写入文本", e);
        }
    }

    @Override
    public void putStream(InputStream stream) {
        if (!isFileProtocol) {
            throw new IllegalArgumentException("URL资源错误: 地址 [" + locationUrl + "] 协议 [" + (locationUrl == null ? null : locationUrl.getProtocol()) + "] 的资源不支持写入!");
        }

        // 首先确保该目录存在
        File curFile = getFile();
        if (!curFile.getParentFile().exists()) curFile.getParentFile().mkdirs();

        try {
            OutputStream os = new FileOutputStream(curFile);
            ObjectUtil.copyStream(stream, os);
            stream.close();
            os.close();
            this.exists = null;
        } catch (IOException e) {
            throw new BaseException("URL资源错误: 文件 [" + curFile.getAbsolutePath() + "] 写入流错误!", e);
        }
    }

    @Override
    public void move(final String newLocation) {
        if (newLocation == null || newLocation.isEmpty())
            throw new IllegalArgumentException("URL资源错误: 未指定地址, 未移动资源到 [" + getLocation() + "]!");
        ResourceReference newRr = createNew(newLocation);

        if (!newRr.getUrl().getProtocol().equals("file"))
            throw new IllegalArgumentException("URL资源错误: 地址 [" + newLocation + "] 不是文件地址, 未移动资源到 [" + getLocation() + "]!");
        if (!isFileProtocol)
            throw new IllegalArgumentException("URL资源错误: 协议 [" + (locationUrl == null ? null : locationUrl.getProtocol()) + "] 地址 [" + locationUrl + "] 的资源不支持移动!");

        File curFile = getFile();
        if (!curFile.exists()) return;

        String path = newRr.getUrl().toExternalForm().substring(5);
        File newFile = new File(path);
        File newFileParent = newFile.getParentFile();
        if (newFileParent != null && !newFileParent.exists()) newFileParent.mkdirs();
        curFile.renameTo(newFile);
    }

    @Override
    public ResourceReference makeDirectory(final String name) {
        if (!isFileProtocol) {
            final URL url = locationUrl;
            throw new IllegalArgumentException("URL资源错误: 带有协议 [" + (url == null ? null : url.getProtocol()) + "] 地址 [" + getLocation() + "] 的资源不支持写入!");
        }

        UrlResourceReference newRef = (UrlResourceReference) new UrlResourceReference().init(getLocation() + "/" + name);
        newRef.getFile().mkdirs();
        return newRef;
    }

    @Override
    public ResourceReference makeFile(final String name) {
        if (!isFileProtocol) {
            final URL url = locationUrl;
            throw new IllegalArgumentException("URL资源错误: 带有协议 [" + (url == null ? null : url.getProtocol()) + "] 地址 [" + getLocation() + "] 的资源不支持写入!");
        }

        UrlResourceReference newRef = (UrlResourceReference) new UrlResourceReference().init(getLocation() + "/" + name);
        // 首先确保该目录存在
        if (!getFile().exists()) getFile().mkdirs();
        try {
            newRef.getFile().createNewFile();
            return newRef;
        } catch (IOException e) {
            throw new BaseException("URL资源错误: 地址 [" + newRef.getLocation() + "] 的文件无法写入文本!", e);
        }
    }

    @Override
    public boolean delete() {
        if (!isFileProtocol) {
            final URL url = locationUrl;
            throw new IllegalArgumentException("URL资源错误: 带有协议 [" + (url == null ? null : url.getProtocol()) + "] 地址 [" + getLocation() + "] 的资源不支持写入!");
        }

        return getFile().delete();
    }
}
