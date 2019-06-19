package com.zmtech.zkit.util;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * 缓存ClassLoader，允许在运行时将JAR文件和类目录添加到类路径。
 * 这将首先从其类目录和JAR文件加载资源，然后尝试父级。 这不是标准方法，但需要在zkit / runtime和组件中进行配置以覆盖其他类路径资源。
 * 这将首先从父级加载类，然后加载其类目录和JAR文件。
 */
public class ZClassLoader extends ClassLoader {
    private static final boolean checkJars = false;
    // 记住 ClassNotFound 导致Groovy出现问题，尝试加载类名，然后创建它们，然后再次尝试
    private static final boolean rememberClassNotFound = false;
    private static final boolean rememberResourceNotFound = true;
    // 未知跟踪：这里一些工具组件如果使用20MB内存并且真的对 启动/其他 没有多大帮助
    private static boolean trackKnown = false;

    private static final Map<String, Class<?>> commonJavaClassesMap = createCommonJavaClassesMap();
    private static Map<String, Class<?>> createCommonJavaClassesMap() {
        Map<String, Class<?>> m = new HashMap<>();
        m.put("java.lang.String",java.lang.String.class); m.put("String", java.lang.String.class);
        m.put("java.lang.CharSequence",java.lang.CharSequence.class); m.put("CharSequence", java.lang.CharSequence.class);
        m.put("java.sql.Timestamp", java.sql.Timestamp.class); m.put("Timestamp", java.sql.Timestamp.class);
        m.put("java.sql.Time", java.sql.Time.class); m.put("Time", java.sql.Time.class);
        m.put("java.sql.Date", java.sql.Date.class); m.put("Date", java.sql.Date.class);
        m.put("java.util.Locale", Locale.class); m.put("java.util.TimeZone", TimeZone.class);
        m.put("java.lang.Byte", java.lang.Byte.class); m.put("java.lang.Character", java.lang.Character.class);
        m.put("java.lang.Integer", java.lang.Integer.class); m.put("Integer", java.lang.Integer.class);
        m.put("java.lang.Long", java.lang.Long.class); m.put("Long", java.lang.Long.class);
        m.put("java.lang.Short", java.lang.Short.class);
        m.put("java.lang.Float", java.lang.Float.class); m.put("Float", java.lang.Float.class);
        m.put("java.lang.Double", java.lang.Double.class); m.put("Double", java.lang.Double.class);
        m.put("java.math.BigDecimal", java.math.BigDecimal.class); m.put("BigDecimal", java.math.BigDecimal.class);
        m.put("java.math.BigInteger", java.math.BigInteger.class); m.put("BigInteger", java.math.BigInteger.class);
        m.put("java.lang.Boolean", java.lang.Boolean.class); m.put("Boolean", java.lang.Boolean.class);
        m.put("java.lang.Object", java.lang.Object.class); m.put("Object", java.lang.Object.class);
        m.put("java.sql.Blob", java.sql.Blob.class); m.put("Blob", java.sql.Blob.class);
        m.put("java.nio.ByteBuffer", java.nio.ByteBuffer.class);
        m.put("java.sql.Clob", java.sql.Clob.class); m.put("Clob", java.sql.Clob.class);
        m.put("java.util.Date", Date.class);
        m.put("java.util.Collection", Collection.class); m.put("Collection", Collection.class);
        m.put("java.util.List", List.class); m.put("List", List.class);
        m.put("java.util.ArrayList", ArrayList.class); m.put("ArrayList", ArrayList.class);
        m.put("java.util.Map", Map.class); m.put("Map", Map.class); m.put("java.util.HashMap", HashMap.class);
        m.put("java.util.Set", Set.class); m.put("Set", Set.class); m.put("java.util.HashSet", HashSet.class);
        m.put("groovy.util.Node", groovy.util.Node.class); m.put("Node", groovy.util.Node.class);
        m.put("org.moqui.util.MNode", com.zmtech.zkit.util.MNode.class); m.put("MNode", com.zmtech.zkit.util.MNode.class);
        m.put(Boolean.TYPE.getName(), Boolean.TYPE); m.put(Short.TYPE.getName(), Short.TYPE);
        m.put(Integer.TYPE.getName(), Integer.TYPE); m.put(Long.TYPE.getName(), Long.TYPE);
        m.put(Float.TYPE.getName(), Float.TYPE); m.put(Double.TYPE.getName(), Double.TYPE);
        m.put(Byte.TYPE.getName(), Byte.TYPE); m.put(Character.TYPE.getName(), Character.TYPE);
        m.put("long[]", long[].class); m.put("char[]", char[].class);
        return m;
    }

    public static Class<?> getCommonClass(String className) { return commonJavaClassesMap.get(className); }
    public static void addCommonClass(String className, Class<?> cls) { commonJavaClassesMap.putIfAbsent(className, cls); }

    private final ArrayList<JarFile> jarFileList = new ArrayList<>();
    private final Map<String, URL> jarLocationByJarName = new HashMap<>();
    private final ArrayList<File> classesDirectoryList = new ArrayList<>();
    private final Map<String, String> jarByClass = new HashMap<>();

    private final HashMap<String, File> knownClassFiles = new HashMap<>();
    private final HashMap<String, JarEntryInfo> knownClassJarEntries = new HashMap<>();
    private static class JarEntryInfo {
        JarEntry entry; JarFile file; URL jarLocation;
        JarEntryInfo(JarEntry je, JarFile jf, URL loc) { entry = je; file = jf; jarLocation = loc; }
    }


    // 此Map包含一个Class或ClassNotFoundException，缓存以便快速访问，因为Groovy遇到了许多奇怪的无效类名，导致创建很多无效的ClassNotFoundException实例
    private final ConcurrentHashMap<String, Class> classCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ClassNotFoundException> notFoundCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, URL> resourceCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ArrayList<URL>> resourceAllCache = new ConcurrentHashMap<>();
    private final Set<String> resourcesNotFound = new HashSet<>();
    private ProtectionDomain pd;

    public ZClassLoader(ClassLoader parent) {
        super(parent);

        if (parent == null) throw new IllegalArgumentException("Parent ClassLoader cannot be null");
        System.out.println("用父类 ["+ parent.getClass().getName()+"] 启动ZCassLoader ");

        pd = getClass().getProtectionDomain();

        for (Map.Entry<String, Class<?>> commonClassEntry: commonJavaClassesMap.entrySet())
            classCache.put(commonClassEntry.getKey(), commonClassEntry.getValue());
    }

    public void addJarFile(JarFile jf, URL jarLocation) {
        jarFileList.add(jf);
        jarLocationByJarName.put(jf.getName(), jarLocation);

        String jfName = jf.getName();
        Enumeration<JarEntry> jeEnum = jf.entries();
        while (jeEnum.hasMoreElements()) {
            JarEntry je = jeEnum.nextElement();
            if (je.isDirectory()) continue;
            String jeName = je.getName();
            if (!jeName.endsWith(".class")) continue;
            String className = jeName.substring(0, jeName.length() - 6).replace('/', '.');

            if (classCache.containsKey(className)) {
                System.out.println("忽略 ["+jfName+"] jar包里的重复类 [" + className + "] in jar ");
                continue;
            }
            if (trackKnown) knownClassJarEntries.put(className, new JarEntryInfo(je, jf, jarLocation));

            /* NOTE: can't do this as classes are defined out of order, end up with NoClassDefFoundError for dependencies:
            Class<?> cls = makeClass(className, jf, je);
            if (cls != null) classCache.put(className, cls);
            */

            if (checkJars) {
                try {
                    getParent().loadClass(className);
                    System.out.println("jar包 ["+jfName+"] 里面的类 ["+className+"] 已经被父 ClassLoader 加载了");
                } catch (ClassNotFoundException e) { /* hoping class is not found! */ }
                if (jarByClass.containsKey(className)) {
                    System.out.println("jar包 ["+jfName+"] 里面的类 ["+className+"] 已经从 jar包 ["+jarByClass.get(className)+"] 里加载了");
                } else {
                    jarByClass.put(className, jfName);
                }
            }
        }
    }
    //List<JarFile> getJarFileList() { return jarFileList; }
    //Map<String, Class> getClassCache() { return classCache; }
    //Map<String, URL> getResourceCache() { return resourceCache; }

    public void addClassesDirectory(File classesDir) {
        if (!classesDir.exists()) throw new IllegalArgumentException("类加载错误: 类文件夹 ["+classesDir+"] 不存在!");
        if (!classesDir.isDirectory()) throw new IllegalArgumentException("类加载错误: ["+classesDir+"] 不是文件夹!");
        classesDirectoryList.add(classesDir);
        findClassFiles("", classesDir);
    }
    private void findClassFiles(String pathSoFar, File dir) {
        File[] children = dir.listFiles();
        if (children == null) return;
        String pathSoFarDot = pathSoFar.concat(".");
        for (File child : children) {
            String fileName = child.getName();
            if (child.isDirectory()) {
                findClassFiles(pathSoFarDot.concat(fileName), child);
            } else if (fileName.endsWith(".class")) {
                String className = pathSoFarDot.concat(fileName.substring(0, fileName.length() - 6));
                if (knownClassFiles.containsKey(className)) {
                    System.out.println("忽略 [" + child.getPath() + "] 里重复的类 [" + className + "]");
                    continue;
                }
                if (trackKnown) knownClassFiles.put(className, child);

                /* 注意：不能这样做，因为类是按顺序定义的，最后是依赖项的NoClassDefFoundError：
                Class<?> cls = makeClass(className, child);
                if (cls != null) classCache.put(className, cls);
                */
            }
        }
    }

    public void clearNotFoundInfo() {
        notFoundCache.clear();
        resourcesNotFound.clear();
    }


    /** @see java.lang.ClassLoader#getResource(String) */
    @Override
    public URL getResource(String name) {
        return findResource(name);
    }

    /** @see java.lang.ClassLoader#getResources(String) */
    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        return findResources(name);
    }

    /** @see java.lang.ClassLoader#findResource(java.lang.String) */
    @Override
    protected URL findResource(String resourceName) {
        URL cachedUrl = resourceCache.get(resourceName);
        if (cachedUrl != null) return cachedUrl;
        if (rememberResourceNotFound && resourcesNotFound.contains(resourceName)) return null;

        // Groovy查找 BeanInfo 和 Customizer groovy资源，即使匿名脚本会存在
        if (rememberResourceNotFound) {
            if ((resourceName.endsWith("BeanInfo.groovy") || resourceName.endsWith("Customizer.groovy")) &&
                    (resourceName.startsWith("script") || resourceName.contains("_actions") || resourceName.contains("_condition"))) {
                resourcesNotFound.add(resourceName);
                return null;
            }
        }

        URL resourceUrl = null;
        for (File classesDir : classesDirectoryList) {
            File testFile = new File(classesDir.getAbsolutePath() + "/" + resourceName);
            try {
                if (testFile.exists() && testFile.isFile()) resourceUrl = testFile.toURI().toURL();
            } catch (MalformedURLException e) {
                System.out.println("类文件夹 [" + classesDir + "] 的URL [" + resourceName + "] 创建错误: " + e.toString());
            }
        }

        if (resourceUrl == null) {
            for (JarFile jarFile : jarFileList) {
                JarEntry jarEntry = jarFile.getJarEntry(resourceName);
                if (jarEntry != null) {
                    try {
                        String jarFileName = jarFile.getName();
                        if (jarFileName.contains("\\")) jarFileName = jarFileName.replace('\\', '/');
                        resourceUrl = new URL("jar:file:" + jarFileName + "!/" + jarEntry);
                    } catch (MalformedURLException e) {
                        System.out.println("jar包 ["+jarFile+"] 的URL ["+resourceName+"] 创建错误: " + e.toString());
                    }
                }
            }
        }

        if (resourceUrl == null) resourceUrl = getParent().getResource(resourceName);
        if (resourceUrl != null) {
            // System.out.println("finding resource " + resourceName + " got " + resourceUrl.toExternalForm());
            URL existingUrl = resourceCache.putIfAbsent(resourceName, resourceUrl);
            if (existingUrl != null) return existingUrl;
            else return resourceUrl;
        } else {
            // 为了测试资源未找到缓存是否正常工作，应该为每个未找到的资源看一次
            // System.out.println("Classpath resource not found with name " + resourceName);
            if (rememberResourceNotFound) resourcesNotFound.add(resourceName);
            return null;
        }
    }

    /** @see java.lang.ClassLoader#findResources(java.lang.String) */
    @Override
    public Enumeration<URL> findResources(String resourceName) throws IOException {
        ArrayList<URL> cachedUrls = resourceAllCache.get(resourceName);
        if (cachedUrls != null) return Collections.enumeration(cachedUrls);

        ArrayList<URL> urlList = new ArrayList<>();
        for (File classesDir : classesDirectoryList) {
            File testFile = new File(classesDir.getAbsolutePath() + "/" + resourceName);
            try {
                if (testFile.exists() && testFile.isFile()) urlList.add(testFile.toURI().toURL());
            } catch (MalformedURLException e) {
                System.out.println("类文件夹 [" + classesDir + "] 的URL [" + resourceName + "] 创建错误: " + e.toString());
            }
        }

        for (JarFile jarFile : jarFileList) {
            JarEntry jarEntry = jarFile.getJarEntry(resourceName);
            if (jarEntry != null) {
                try {
                    String jarFileName = jarFile.getName();
                    if (jarFileName.contains("\\")) jarFileName = jarFileName.replace('\\', '/');
                    urlList.add(new URL("jar:file:" + jarFileName + "!/" + jarEntry));
                } catch (MalformedURLException e) {
                    System.out.println("jar包 [" + jarFile + "] 的URL [" + resourceName + "] 创建错误: " + e.toString());
                }
            }
        }
        // 添加父加载程序中找到的所有资源
        Enumeration<URL> superResources = getParent().getResources(resourceName);
        while (superResources.hasMoreElements()) urlList.add(superResources.nextElement());
        resourceAllCache.putIfAbsent(resourceName, urlList);
        // System.out.println("finding all resources with name " + resourceName + " got " + urlList);
        return Collections.enumeration(urlList);
    }

    /** @see java.lang.ClassLoader#getResourceAsStream(String) */
    @Override
    public InputStream getResourceAsStream(String name) {
        URL resourceUrl = findResource(name);
        if (resourceUrl == null) {
            // System.out.println("Classpath resource not found with name " + name);
            return null;
        }
        try {
            return resourceUrl.openStream();
        } catch (IOException e) {
            System.out.println("无法连接类路径 ["+name+"] : " + e.toString());
            return null;
        }
    }

    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException { return loadClass(name, false); }

    @Override
    protected Class<?> loadClass(String className, boolean resolve) throws ClassNotFoundException {
        Class cachedClass = classCache.get(className);
        if (cachedClass != null) return cachedClass;
        if (rememberClassNotFound) {
            ClassNotFoundException cachedExc = notFoundCache.get(className);
            if (cachedExc != null) throw cachedExc;
        }

        return loadClassInternal(className, resolve);
    }

    // private static final ArrayList<String> ignoreSuffixes = new ArrayList<>(Arrays.asList("Customizer", "BeanInfo"));
    // private static final int ignoreSuffixesSize = ignoreSuffixes.size();
    // TODO: does this need synchronized? slows it down...
    private Class<?> loadClassInternal(String className, boolean resolve) throws ClassNotFoundException {
        /* Groovy寻找各种伪造的类名，但可能有一个原因，所以不这样做或寻找其他模式:
        for (int i = 0; i < ignoreSuffixesSize; i++) {
            String ignoreSuffix = ignoreSuffixes.get(i);
            if (className.endsWith(ignoreSuffix)) {
                ClassNotFoundException cfne = new ClassNotFoundException("Ignoring Groovy style bogus class name " + className);
                classCache.put(className, cfne);
                throw cfne;
            }
        }
        */

        Class<?> c = null;
        try {
            // 处理与资源相反的类，先尝试父级（避免java.lang.LinkageError）
            try {
                ClassLoader cl = getParent();
                c = cl.loadClass(className);
            } catch (ClassNotFoundException|NoClassDefFoundError e) {
                // 什么都不做，如果期望在其他JAR和类目录中找不到类，则通常不会找到该类
            } catch (RuntimeException e) {
                e.printStackTrace();
                throw e;
            }

            if (c == null) {
                try {
                    if (trackKnown) {
                        File classFile = knownClassFiles.get(className);
                        if (classFile != null) c = makeClass(className, classFile);
                        if (c == null) {
                            JarEntryInfo jei = knownClassJarEntries.get(className);
                            if (jei != null) c = makeClass(className, jei.file, jei.entry, jei.jarLocation);
                        }
                    }

                    // 在已知中找不到？ 搜索所有
                    c = findJarClass(className);
                } catch (Exception e) {
                    System.out.println("无法加载其他jar包中的类 ["+className+"] : " + e.toString());
                    e.printStackTrace();
                }
            }

            // System.out.println("Loading class name [" + className + "] got class: " + c);
            if (c == null) {
                ClassNotFoundException cnfe = new ClassNotFoundException("Class " + className + " not found.");
                if (rememberClassNotFound) {
                    // 有些名字，Groovy会找完了再找:
                    //     groovy.lang.GroovyObject$java$io$org$moqui$entity$EntityListIterator
                    //     java.io.org$moqui$entity$EntityListIterator
                    //     groovy.util.org$moqui$context$ExecutionContext
                    //     org$moqui$context$ExecutionContext
                    // Groovy与* Customizer和* BeanInfo类似; 所以只是这些
                    // In general it seems that anything with a '$' needs to be excluded
                    if (!className.contains("$") && !className.endsWith("Customizer") && !className.endsWith("BeanInfo")) {
                        ClassNotFoundException existingExc = notFoundCache.putIfAbsent(className, cnfe);
                        if (existingExc != null) throw existingExc;
                    }
                }
                throw cnfe;
            } else {
                classCache.put(className, c);
            }
            return c;
        } finally {
            if (c != null && resolve) resolveClass(c);
        }
    }

    private ConcurrentHashMap<URL, ProtectionDomain> protectionDomainByUrl = new ConcurrentHashMap<>();
    private ProtectionDomain getProtectionDomain(URL jarLocation) {
        ProtectionDomain curPd = protectionDomainByUrl.get(jarLocation);
        if (curPd != null) return curPd;
        CodeSource codeSource = new CodeSource(jarLocation, (Certificate[]) null);
        ProtectionDomain newPd = new ProtectionDomain(codeSource, null, this, null);
        ProtectionDomain existingPd = protectionDomainByUrl.putIfAbsent(jarLocation, newPd);
        return existingPd != null ? existingPd : newPd;
    }

    private Class<?> makeClass(String className, File classFile) {
        try {
            byte[] jeBytes = getFileBytes(classFile);
            if (jeBytes == null) {
                System.out.println("无法获取类文件 ["+classFile+"] 中的字节");
                return null;
            }
            return defineClass(className, jeBytes, 0, jeBytes.length, pd);
        } catch (Throwable t) {
            System.out.println("无法读取类 ["+classFile+"] 文件: " + t.toString());
            return null;
        }
    }
    private Class<?> makeClass(String className, JarFile file, JarEntry entry, URL jarLocation) {
        try {
            definePackage(className, file);
            byte[] jeBytes = getJarEntryBytes(file, entry);
            if (jeBytes == null) {
                System.out.println("无法读取jar包 ["+file.getName()+"] 的输入 ["+entry.getName()+"] 中的字节");
                return null;
            } else {
                // System.out.println("Loading class " + className + " from " + entry.getName() + " in " + file.getName());
                return defineClass(className, jeBytes, 0, jeBytes.length, jarLocation != null ? getProtectionDomain(jarLocation) : pd);
            }
        } catch (Throwable t) {
            System.out.println("无法读取jar包 ["+file.getName()+"] 中的类文件 ["+entry.getName()+"]: " + t.toString());
            return null;
        }
    }
    @SuppressWarnings("ThrowFromFinallyBlock")
    private byte[] getJarEntryBytes(JarFile jarFile, JarEntry je) throws IOException {
        DataInputStream dis = null;
        byte[] jeBytes = null;
        try {
            long lSize = je.getSize();
            if (lSize <= 0 || lSize >= Integer.MAX_VALUE)
                throw new IllegalArgumentException("jar包 ["+je+"] 输入的磁盘空间 ["+lSize+"] 错误");
            jeBytes = new byte[(int) lSize];
            InputStream is = jarFile.getInputStream(je);
            dis = new DataInputStream(is);
            dis.readFully(jeBytes);
        } finally {
            if (dis != null) dis.close();
        }
        return jeBytes;
    }

    @SuppressWarnings("ThrowFromFinallyBlock")
    private byte[] getFileBytes(File classFile) throws IOException {
        DataInputStream dis = null;
        byte[] jeBytes = null;
        try {
            long lSize = classFile.length();
            if (lSize <= 0  ||  lSize >= Integer.MAX_VALUE) {
                throw new IllegalArgumentException("类文件 ["+classFile+"] 所占空间 ["+lSize+"] 错误");
            }
            jeBytes = new byte[(int)lSize];
            InputStream is = new FileInputStream(classFile);
            dis = new DataInputStream(is);
            dis.readFully(jeBytes);
        } finally {
            if (dis != null) dis.close();
        }
        return jeBytes;
    }

    private Class<?> findJarClass(String className) throws IOException, ClassFormatError, ClassNotFoundException {
        Class cachedClass = classCache.get(className);
        if (cachedClass != null) return cachedClass;
        if (rememberClassNotFound) {
            ClassNotFoundException cachedExc = notFoundCache.get(className);
            if (cachedExc != null) throw cachedExc;
        }

        Class<?> c = null;
        String classFileName = className.replace('.', '/').concat(".class");

        for (File classesDir : classesDirectoryList) {
            File testFile = new File(classesDir.getAbsolutePath() + "/" + classFileName);
            if (testFile.exists() && testFile.isFile()) {
                c = makeClass(className, testFile);
                if (c != null) break;
            }
        }

        if (c == null) {
            for (JarFile jarFile : jarFileList) {
                // System.out.println("Finding class file " + classFileName + " in jar file " + jarFile.getName());
                JarEntry jarEntry = jarFile.getJarEntry(classFileName);
                if (jarEntry != null) {
                    c = makeClass(className, jarFile, jarEntry, jarLocationByJarName.get(jarFile.getName()));
                    break;
                }
            }
        }

        // 在这里只有缓存，如果存在
        if (c != null) {
            Class existingClass = classCache.putIfAbsent(className, c);
            if (existingClass != null) return existingClass;
            else return c;
        } else {
            return null;
        }
    }

    private void definePackage(String className, JarFile jarFile) throws IllegalArgumentException {
        Manifest mf = null;
        try {
            mf = jarFile.getManifest();
        } catch (IOException e) {
            System.out.println("无法从 ["+jarFile.getName()+"] 中获取 manifest: " + e.toString());
        }
        // if no manifest use default
        if (mf == null) mf = new Manifest();

        int dotIndex = className.lastIndexOf('.');
        String packageName = dotIndex > 0 ? className.substring(0, dotIndex) : "";
        if (getPackage(packageName) == null) {
            definePackage(packageName,
                    mf.getMainAttributes().getValue(Attributes.Name.SPECIFICATION_TITLE),
                    mf.getMainAttributes().getValue(Attributes.Name.SPECIFICATION_VERSION),
                    mf.getMainAttributes().getValue(Attributes.Name.SPECIFICATION_VENDOR),
                    mf.getMainAttributes().getValue(Attributes.Name.IMPLEMENTATION_TITLE),
                    mf.getMainAttributes().getValue(Attributes.Name.IMPLEMENTATION_VERSION),
                    mf.getMainAttributes().getValue(Attributes.Name.IMPLEMENTATION_VENDOR),
                    getSealURL(mf));
        }
    }

    private URL getSealURL(Manifest mf) {
        String seal = mf.getMainAttributes().getValue(Attributes.Name.SEALED);
        if (seal == null) return null;
        try {
            return new URL(seal);
        } catch (MalformedURLException e) {
            return null;
        }
    }
}
