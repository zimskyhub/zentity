package com.zmtech.zkit.references;

import com.zmtech.zkit.exception.BaseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.activation.MimetypesFileTypeMap;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.sql.Timestamp;
import java.util.*;

public abstract class ResourceReference implements Serializable {
    private static final Logger logger = LoggerFactory.getLogger(ResourceReference.class);
    private static final MimetypesFileTypeMap mimetypesFileTypeMap = new MimetypesFileTypeMap();

    protected ResourceReference childOfResource = null;
    private Map<String, ResourceReference> subContentRefByPath = null;

    public abstract ResourceReference init(String location);

    public abstract ResourceReference createNew(String location);

    public abstract String getLocation();
    public abstract InputStream openStream();
    public abstract OutputStream getOutputStream();
    public abstract String getText();

    public abstract boolean supportsAll();
    public abstract boolean supportsUrl();
    public abstract URL getUrl();

    public abstract boolean supportsDirectory();
    public abstract boolean isFile();
    public abstract boolean isDirectory();

    public abstract boolean supportsExists();
    public abstract boolean getExists();

    public abstract boolean supportsLastModified();
    public abstract long getLastModified();

    public abstract boolean supportsSize();
    public abstract long getSize();

    public abstract boolean supportsWrite();
    public abstract void putText(String text);
    public abstract void putStream(InputStream stream);
    public abstract void move(String newLocation);
    public abstract ResourceReference makeDirectory(String name);
    public abstract ResourceReference makeFile(String name);
    public abstract boolean delete();

    /** 获取目录的入口 */
    public abstract List<ResourceReference> getDirectoryEntries();

    public URI getUri() {
        try {
            if (supportsUrl()) {
                URL locUrl = getUrl();
                if (locUrl == null) return null;
                // 使用多参数构造函数使其执行字符编码并避免异常
                // 警告：此URI中的字符串可能与URL中的字符串不相等（即，如果字符是编码的）
                // 注意：对于本地文件，这似乎不适用于Windows：当协议是普通的“文件”并且路径以“C：\ moqui \ ...”之类的驱动器号开头时，
                // 它会产生一个解析错误，显示URI为“文件：// C：/...”
                if (logger.isTraceEnabled()) logger.trace("Getting URI for URL " + locUrl.toExternalForm());
                String path = locUrl.getPath();

                // 支持Windows本地文件。
                if ("file".equals(locUrl.getProtocol())) {
                    if (!path.startsWith("/"))
                        path = "/" + path;
                }
                return new URI(locUrl.getProtocol(), locUrl.getUserInfo(), locUrl.getHost(),
                        locUrl.getPort(), path, locUrl.getQuery(), locUrl.getRef());
            } else {
                String loc = getLocation();
                if (loc == null || loc.isEmpty()) return null;
                return new URI(loc);
            }
        } catch (URISyntaxException e) {
            throw new BaseException("资源引用错误: 无法创建 URI", e);
        }
    }

    /** URI的一部分不容易从URI对象中获取，基本上是路径的最后部分。 */
    public String getFileName() {
        String loc = getLocation();
        if (loc == null || loc.length() == 0) return null;
        int slashIndex = loc.lastIndexOf("/");
        return slashIndex >= 0 ? loc.substring(slashIndex + 1) : loc;
    }


    /** 如果可以确定此内容的类型（MIME）。 */
    public String getContentType() {
        String fn = getFileName();
        return fn != null && fn.length() > 0 ? getContentType(fn) : null;
    }
    public boolean isBinary() { return isBinaryContentType(getContentType()); }

    /** 获取父目录，如果是根目录（无父目录），则为null。 */
    public ResourceReference getParent() {
        String curLocation = getLocation();
        if (curLocation.endsWith("/")) curLocation = curLocation.substring(0, curLocation.length() - 1);
        String strippedLocation = stripLocationPrefix(curLocation);
        if (strippedLocation.isEmpty()) return null;
        if (strippedLocation.startsWith("/")) strippedLocation = strippedLocation.substring(1);
        if (strippedLocation.contains("/")) {
            return createNew(curLocation.substring(0, curLocation.lastIndexOf("/")));
        } else {
            String prefix = getLocationPrefix(curLocation);
            if (!prefix.isEmpty()) return createNew(prefix);
            return null;
        }
    }

    /** 找到名称与当前文件名匹配的目录（减去扩展名） */
    public ResourceReference findMatchingDirectory() {
        if (this.isDirectory()) return this;
        StringBuilder dirLoc = new StringBuilder(getLocation());
        ResourceReference directoryRef = this;
        while (!(directoryRef.getExists() && directoryRef.isDirectory()) && dirLoc.lastIndexOf(".") > 0) {
            // 一次去掉一个后缀（屏幕可能是.xml但是使用。*用于其他文件等）
            dirLoc.delete(dirLoc.lastIndexOf("."), dirLoc.length());
            directoryRef = createNew(dirLoc.toString());
            // directoryRef = ecf.resource.getLocationReference(dirLoc.toString())
        }
        return directoryRef;
    }

    /** 获取对此目录的子项或匹配目录中此文件的引用 */
    public ResourceReference getChild(String childName) {
        if (childName == null || childName.length() == 0) return null;
        ResourceReference directoryRef = findMatchingDirectory();
        StringBuilder fileLoc = new StringBuilder(directoryRef.getLocation());
        if (fileLoc.charAt(fileLoc.length()-1) == '/') fileLoc.deleteCharAt(fileLoc.length()-1);
        if (childName.charAt(0) != '/') fileLoc.append('/');
        fileLoc.append(childName);

        // 注意：此时并不关心它是否存在
        return createNew(fileLoc.toString());
    }

    /** 获取此目录中所有文件或匹配目录中文件的引用列表 */
    public List<ResourceReference> getChildren() {
        List<ResourceReference> children = new LinkedList<>();
        ResourceReference directoryRef = findMatchingDirectory();
        if (directoryRef == null || !directoryRef.getExists()) return null;
        for (ResourceReference childRef : directoryRef.getDirectoryEntries()) if (childRef.isFile()) {
            children.add(childRef);
        }
        return children;
    }

    /** 在匹配目录和子匹配目录中按路径（可以是单个名称）查找文件 */
    public ResourceReference findChildFile(String relativePath) {
        // 没有子文件路径？ 这意味着这个资源
        if (relativePath == null || relativePath.length() == 0) return this;

        if (!supportsAll()) {
            throw new BaseException("资源引用错误: 无法在根路径 ["+getLocation()+"] 下找子文件 ["+relativePath+"] ,因为不支持 exists, isFile 等");
        }

        // logger.warn("============= finding child resource of [${toString()}] path [${relativePath}]")

        // 首先检查缓存
        ResourceReference childRef = getSubContentRefByPath().get(relativePath);
        if (childRef != null && childRef.getExists()) return childRef;

        // 这将在与此资源同名的目录中查找文件，除非此资源是目录
        ResourceReference directoryRef = findMatchingDirectory();

        // logger.warn("============= finding child resource path [${relativePath}] directoryRef [${directoryRef}]")
        if (directoryRef.getExists()) {
            StringBuilder fileLoc = new StringBuilder(directoryRef.getLocation());
            if (fileLoc.charAt(fileLoc.length() - 1) == '/') fileLoc.deleteCharAt(fileLoc.length() - 1);
            if (relativePath.charAt(0) != '/') fileLoc.append('/');
            fileLoc.append(relativePath);

            ResourceReference theFile = createNew(fileLoc.toString());
            if (theFile.getExists() && theFile.isFile()) childRef = theFile;

            // logger.warn("============= finding child resource path [${relativePath}] childRef [${childRef}]")
            if (childRef == null) {
                // 没有在文字路径上找到它，尝试在所有子目录中搜索它
                int lastSlashIdx = relativePath.lastIndexOf("/");
                String directoryPath = lastSlashIdx > 0 ? relativePath.substring(0, lastSlashIdx) : "";
                String childFilename = lastSlashIdx >= 0 ? relativePath.substring(lastSlashIdx + 1) : relativePath;
                // 首先找到最匹配的目录
                ResourceReference childDirectoryRef = directoryRef.findChildDirectory(directoryPath);
                // 递归遍历目录树并找到子文件名称
                childRef = internalFindChildFile(childDirectoryRef, childFilename);
                // logger.warn("============= finding child resource path [${relativePath}] directoryRef [${directoryRef}] childFilename [${childFilename}] childRef [${childRef}]")
            }

            // logger.warn("============= finding child resource path [${relativePath}] childRef 3 [${childRef}]")
            if (childRef != null) childRef.childOfResource = directoryRef;
        }


        if (childRef == null) {
            // 依然没有？ 将文件的路径视为文字并返回它（存在将为false）
            if (directoryRef.getExists()) {
                childRef = createNew(directoryRef.getLocation() + "/" + relativePath);
                childRef.childOfResource = directoryRef;
            } else {
                String newDirectoryLoc = getLocation();
                // 弹出扩展，一切都经过最后一个斜线后的第一个点
                int lastSlashLoc = newDirectoryLoc.lastIndexOf("/");
                if (newDirectoryLoc.contains("."))
                    newDirectoryLoc = newDirectoryLoc.substring(0, newDirectoryLoc.indexOf(".", lastSlashLoc));
                childRef = createNew(newDirectoryLoc + "/" + relativePath);
            }
        } else {
            // 在返回之前将它放在缓存中，但不要缓存文字引用
            getSubContentRefByPath().put(relativePath, childRef);
        }

        // logger.warn("============= finding child resource of [${toString()}] path [${relativePath}] got [${childRef}]")
        return childRef;
    }

    /** 在匹配目录和子匹配目录中按路径（可以是单个名称）查找目录 */
    public ResourceReference findChildDirectory(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) return this;

        if (!supportsAll()) {
            throw new BaseException("资源引用错误: 无法在根路径 ["+getLocation()+"] 下找子文件 ["+relativePath+"] ,因为不支持 exists, isFile 等");
        }

        // 首先检查缓存
        ResourceReference childRef = getSubContentRefByPath().get(relativePath);
        if (childRef != null && childRef.getExists()) return childRef;

        String[] relativePathNameList = relativePath.split("/");

        ResourceReference childDirectoryRef = this;
        if (this.isFile()) childDirectoryRef = this.findMatchingDirectory();

        // 搜索剩余的 relativePathNameList，引导到文件名的目录
        for (String relativePathName : relativePathNameList) {
            childDirectoryRef = internalFindChildDir(childDirectoryRef, relativePathName);
            if (childDirectoryRef == null) break;
        }


        if (childDirectoryRef == null) {
            // 依然没有？ 将文件的路径视为文字并返回它（存在将为false）
            String newDirectoryLoc = getLocation();
            if (this.isFile()) {
                // 去掉扩展名，一切都经过最后一个斜线后的第一个点
                int lastSlashLoc = newDirectoryLoc.lastIndexOf("/");
                if (newDirectoryLoc.contains("."))
                    newDirectoryLoc = newDirectoryLoc.substring(0, newDirectoryLoc.indexOf(".", lastSlashLoc));
            }

            childDirectoryRef = createNew(newDirectoryLoc + "/" + relativePath);
        } else {
            // 在返回之前将它放在缓存中，但不要缓存文字引用
            getSubContentRefByPath().put(relativePath, childRef);
        }

        return childDirectoryRef;
    }

    private ResourceReference internalFindChildDir(ResourceReference directoryRef, String childDirName) {
        if (directoryRef == null || !directoryRef.getExists()) return null;
        // 没有子文件夹名称，意思是这个/当前的文件夹
        if (childDirName == null || childDirName.isEmpty()) return directoryRef;

        // 尝试取直接子文件夹，如果它在那里它比暴力搜索更有效
        StringBuilder dirLocation = new StringBuilder(directoryRef.getLocation());
        if (dirLocation.charAt(dirLocation.length() - 1) == '/') dirLocation.deleteCharAt(dirLocation.length() - 1);
        if (childDirName.charAt(0) != '/') dirLocation.append('/');
        dirLocation.append(childDirName);
        ResourceReference directRef = createNew(dirLocation.toString());
        if (directRef != null && directRef.getExists()) return directRef;

        // 如果没有找到直接引用，请尝试更灵活的搜索
        for (ResourceReference childRef : directoryRef.getDirectoryEntries()) {
            if (childRef.isDirectory() && (childRef.getFileName().equals(childDirName) || childRef.getFileName().contains(childDirName + "."))) {
                // 匹配文件夹名称，使用它
                return childRef;
            } else if (childRef.isDirectory()) {
                // 不匹配的文件夹名，递归到它
                ResourceReference subRef = internalFindChildDir(childRef, childDirName);
                if (subRef != null) return subRef;
            }
        }
        return null;
    }

    private ResourceReference internalFindChildFile(ResourceReference directoryRef, String childFilename) {
        if (directoryRef == null || !directoryRef.getExists()) return null;

        // 找先检查明确的文件名
        ResourceReference exactMatchRef = directoryRef.getChild(childFilename);
        if (exactMatchRef.isFile() && exactMatchRef.getExists()) return exactMatchRef;

        List<ResourceReference> childEntries = directoryRef.getDirectoryEntries();
        // 首先查看所有文件，即进行广度优先搜索
        for (ResourceReference childRef : childEntries) {
            if (childRef.isFile() && (childRef.getFileName().equals(childFilename) || childRef.getFileName().startsWith(childFilename + "."))) {
                return childRef;
            }
        }

        for (ResourceReference childRef : childEntries) {
            if (childRef.isDirectory()) {
                ResourceReference subRef = internalFindChildFile(childRef, childFilename);
                if (subRef != null) return subRef;
            }
        }
        return null;
    }

    public String getActualChildPath() {
        if (childOfResource == null) return null;
        String parentLocation = childOfResource.getLocation();
        String childLocation = getLocation();
        // 这应该是true，但以防万一：
        if (childLocation.startsWith(parentLocation)) {
            String childPath = childLocation.substring(parentLocation.length());
            if (childPath.startsWith("/")) return childPath.substring(1);
            else return childPath;
        }
        // if not, what to do?
        return null;
    }

    public void walkChildTree(List<Map> allChildFileFlatList, List<Map> childResourceList) {
        if (this.isFile()) walkChildFileTree(this, "", allChildFileFlatList, childResourceList);
        if (this.isDirectory()) for (ResourceReference childRef : getDirectoryEntries()) {
            childRef.walkChildFileTree(this, "", allChildFileFlatList, childResourceList);
        }
    }
    private void walkChildFileTree(ResourceReference rootResource, String pathFromRoot,
                                   List<Map> allChildFileFlatList, List<Map> childResourceList) {
        // logger.warn("================ walkChildFileTree rootResource=${rootResource} pathFromRoot=${pathFromRoot} curLocation=${getLocation()}")
        String childPathBase = pathFromRoot != null && !pathFromRoot.isEmpty() ? pathFromRoot + '/' : "";

        if (this.isFile()) {
            List<Map> curChildResourceList = new LinkedList<>();

            String curFileName = getFileName();
            if (curFileName.contains(".")) curFileName = curFileName.substring(0, curFileName.indexOf('.'));
            String curPath = childPathBase + curFileName;

            if (allChildFileFlatList != null) {
                Map<String, String> infoMap = new HashMap<>(3);
                infoMap.put("path", curPath); infoMap.put("name", curFileName); infoMap.put("location", getLocation());
                allChildFileFlatList.add(infoMap);
            }
            if (childResourceList != null) {
                Map<String, Object> infoMap = new HashMap<>(4);
                infoMap.put("path", curPath); infoMap.put("name", curFileName); infoMap.put("location", getLocation());
                infoMap.put("childResourceList", curChildResourceList);
                childResourceList.add(infoMap);
            }

            ResourceReference matchingDirReference = this.findMatchingDirectory();
            String childPath = childPathBase + matchingDirReference.getFileName();
            for (ResourceReference childRef : matchingDirReference.getDirectoryEntries()) {
                childRef.walkChildFileTree(rootResource, childPath, allChildFileFlatList, curChildResourceList);
            }
        }
        // TODO: 以某种方式走到子文件夹或只是坚持与匹配目录的文件？
    }

    public void destroy() { }
    @Override
    public String toString() {
        String loc = getLocation();
        return loc != null && !loc.isEmpty() ? loc : ("[no location (" + getClass().getName() + ")]");
    }

    private Map<String, ResourceReference> getSubContentRefByPath() {
        if (subContentRefByPath == null) subContentRefByPath = new HashMap<>();
        return subContentRefByPath;
    }

    public static String getContentType(String filename) {
        // 需要检查一下，还是输入mapper处理好了？||！filename.contains（“”）
        if (filename == null || filename.length() == 0) return null;
        String type = mimetypesFileTypeMap.getContentType(filename);
        // 剥离任何参数，即;
        int semicolonIndex = type.indexOf(";");
        if (semicolonIndex >= 0) type = type.substring(0, semicolonIndex);
        return type;
    }
    public static boolean isBinaryContentType(String contentType) {
        if (contentType == null || contentType.length() == 0) return false;
        if (contentType.startsWith("text/")) return false;
        // 除了text / *之外，还有一些值得注意的例外：
        if ("application/javascript".equals(contentType)) return false;
        if ("application/json".equals(contentType)) return false;
        if (contentType.endsWith("+json")) return false;
        if ("application/rtf".equals(contentType)) return false;
        if (contentType.startsWith("application/xml")) return false;
        if (contentType.endsWith("+xml")) return false;
        if (contentType.startsWith("application/yaml")) return false;
        if (contentType.endsWith("+yaml")) return false;
        return true;
    }
    public static String stripLocationPrefix(String location) {
        if (location == null || location.isEmpty()) return "";
        // 首先删除冒号（:)及其前面的所有内容
        StringBuilder strippedLocation = new StringBuilder(location);
        int colonIndex = strippedLocation.indexOf(":");
        if (colonIndex == 0) {
            strippedLocation.deleteCharAt(0);
        } else if (colonIndex > 0) {
            strippedLocation.delete(0, colonIndex+1);
        }
        // 删除所有前导斜杠
        while (strippedLocation.length() > 0 && strippedLocation.charAt(0) == '/') strippedLocation.deleteCharAt(0);
        return strippedLocation.toString();
    }
    public static String getLocationPrefix(String location) {
        if (location == null || location.isEmpty()) return "";
        if (location.contains("://")) {
            return location.substring(0, location.indexOf(":")) + "://";
        } else if (location.contains(":")) {
            return location.substring(0, location.indexOf(":")) + ":";
        } else {
            return "";
        }
    }

    public boolean supportsVersion() { return false; }
    public Version getVersion(String versionName) { return null; }
    public Version getCurrentVersion() { return null; }
    public Version getRootVersion() { return null; }
    public ArrayList<Version> getVersionHistory() { return new ArrayList<>(); }
    public ArrayList<Version> getNextVersions(String versionName) { return new ArrayList<>(); }
    public InputStream openStream(String versionName) { return openStream(); }
    public String getText(String versionName) { return getText(); }

    public static class Version {
        private final ResourceReference ref;
        private final String versionName, previousVersionName, userId;
        private final Timestamp versionDate;
        public Version(ResourceReference ref, String versionName, String previousVersionName, String userId, Timestamp versionDate) {
            this.ref = ref; this.versionName = versionName; this.previousVersionName = previousVersionName;
            this.userId = userId; this.versionDate = versionDate;
        }
        public ResourceReference getRef() { return ref; }
        public String getVersionName() { return versionName; }
        public String getPreviousVersionName() { return previousVersionName; }
        public Version getPreviousVersion() { return ref.getVersion(previousVersionName); }
        public ArrayList<Version> getNextVersions() { return ref.getNextVersions(versionName); }
        public String getUserId() { return userId; }
        public Timestamp getVersionDate() { return versionDate; }
        public InputStream openStream() { return ref.openStream(versionName); }
        public String getText() { return ref.getText(versionName); }
        public Map<String, Object> getMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("versionName", versionName); map.put("previousVersionName", previousVersionName);
            map.put("userId", userId); map.put("versionDate", versionDate);
            return map;
        }
    }
}
