package com.zmtech.zkit.util;

import com.zmtech.zkit.exception.BaseException;
import com.zmtech.zkit.resource.references.ResourceReference;
import freemarker.ext.beans.BeansWrapper;
import freemarker.ext.beans.BeansWrapperBuilder;
import freemarker.template.*;
import groovy.lang.Closure;
import groovy.util.Node;
import groovy.util.NodeList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.SAXParserFactory;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static java.nio.charset.StandardCharsets.UTF_8;

@SuppressWarnings("unused")
public class MNode implements TemplateNodeModel, TemplateSequenceModel, TemplateHashModelEx, AdapterTemplateModel, TemplateScalarModel{

    protected final static Logger logger = LoggerFactory.getLogger(MNode.class);

    private final static Map<String, MNode> parsedNodeCache = new HashMap<>();

    private static final Version FTL_VERSION = Configuration.VERSION_2_3_25;

    public static void clearParsedNodeCache() {
        parsedNodeCache.clear();
    }



    /* ========== XML 解析 ========== */
    public static MNode parse(ResourceReference rr) throws BaseException {
        if (rr == null || !rr.getExists()) return null;
        String location = rr.getLocation();
        MNode cached = parsedNodeCache.get(location);
        if (cached != null && cached.lastModified >= rr.getLastModified()) return cached;

        MNode node = parse(location, rr.openStream());
        node.lastModified = rr.getLastModified();
        if (node.lastModified > 0) parsedNodeCache.put(location, node);
        return node;
    }

    /**
     * 从输入流解析并关闭流
     */
    public static MNode parse(String location, InputStream is) throws BaseException {
        if (is == null) return null;
        try {
            return parse(location, new InputSource(is));
        } finally {
            try {
                is.close();
            } catch (IOException e) {
                logger.error("XML解析错误: 关闭XML输入流错误,文件位置: " + location, e);
            }
        }
    }

    public static MNode parse(File fl) throws BaseException {
        if (fl == null || !fl.exists()) return null;

        String location = fl.getPath();
        MNode cached = parsedNodeCache.get(location);
        if (cached != null && cached.lastModified >= fl.lastModified()) return cached;

        BufferedReader fr = null;
        try {
            fr = Files.newBufferedReader(fl.toPath(), UTF_8); // new FileReader(fl);
            MNode node = parse(fl.getPath(), new InputSource(fr));
            node.lastModified = fl.lastModified();
            if (node.lastModified > 0) parsedNodeCache.put(location, node);
            return node;
        } catch (Exception e) {
            throw new BaseException("XML解析错误: 解析XML文件错误,文件位置: " + fl.getPath(), e);
        } finally {
            try {
                if (fr != null) fr.close();
            } catch (IOException e) {
                logger.error("XML解析错误: 关闭XML输入流错误,文件位置: " + fl.getPath(), e);
            }
        }
    }

    public static MNode parseText(String location, String text) throws BaseException {
        if (text == null || text.length() == 0) return null;
        return parse(location, new InputSource(new StringReader(text)));
    }

    public static MNode parse(String location, InputSource isrc) {
        try {
            MNodeXmlHandler xmlHandler = new MNodeXmlHandler(false);
            XMLReader reader = SAXParserFactory.newInstance().newSAXParser().getXMLReader();
            reader.setContentHandler(xmlHandler);
            reader.parse(isrc);
            return xmlHandler.getRootNode();
        } catch (Exception e) {
            throw new BaseException("XML解析错误: 解析XML文件错误,文件位置: " + location, e);
        }
    }

    public static MNode parseRootOnly(ResourceReference rr) {
        InputStream is = rr.openStream();
        if (is == null) return null;
        try {
            return parseRootOnly(rr.getLocation(), new InputSource(is));
        } finally {
            try { is.close(); }
            catch (IOException e) { logger.error("XML解析错误: 错误关闭XML流文件 " + rr.getLocation(), e); }
        }
    }

    public static MNode parseRootOnly(String location, InputSource isrc) {
        try {
            MNodeXmlHandler xmlHandler = new MNodeXmlHandler(true);
            XMLReader reader = SAXParserFactory.newInstance().newSAXParser().getXMLReader();
            reader.setContentHandler(xmlHandler);
            reader.parse(isrc);
            return xmlHandler.getRootNode();
        } catch (Exception e) {
            throw new BaseException("XML解析错误: 文件位置: " + location, e);
        }
    }

    /* ========== Fields ========== */

    private String nodeName;
    private final Map<String, String> attributeMap = new LinkedHashMap<>();
    private MNode parentNode = null;
    private ArrayList<MNode> childList = null;
    private HashMap<String, ArrayList<MNode>> childrenByName = null;
    private String childText = null;
    private long lastModified = 0;
    private boolean systemExpandAttributes = false;

    /* ========== Constructors ========== */

    public MNode(Node node) {
        nodeName = (String) node.name();
        Set attrEntries = node.attributes().entrySet();
        for (Object entryObj : attrEntries)
            if (entryObj instanceof Map.Entry) {
                Map.Entry entry = (Map.Entry) entryObj;
                if (entry.getKey() != null)
                    attributeMap.put(entry.getKey().toString(), entry.getValue() != null ? entry.getValue().toString() : null);
            }
        for (Object childObj : node.children()) {
            if (childObj instanceof Node) {
                append((Node) childObj);
            } else if (childObj instanceof NodeList) {
                NodeList nl = (NodeList) childObj;
                for (Object nlEntry : nl) {
                    if (nlEntry instanceof Node) {
                        append((Node) nlEntry);
                    }
                }
            }
        }
        childText = gnodeText(node);
        if (childText != null && childText.trim().length() == 0) childText = null;

        // if ("entity".equals(nodeName)) logger.info("Groovy Node:\n" + node + "\n MNode:\n" + toString());
    }

    public MNode(String name, Map<String, String> attributes, MNode parent, List<MNode> children, String text) {
        nodeName = name;
        if (attributes != null) attributeMap.putAll(attributes);
        parentNode = parent;
        if (children != null && children.size() > 0) {
            childList = new ArrayList<>();
            childList.addAll(children);
        }
        if (text != null && text.trim().length() > 0) childText = text;
    }

    public MNode(String name, Map<String, String> attributes) {
        nodeName = name;
        if (attributes != null) attributeMap.putAll(attributes);
    }

    /* ========== 获取方法 ========== */

    /**
     * 如果名称以＆符号（@）开头，则获取属性，否则获取具有给定名称的子节点列表。
     */
    public Object getObject(String name) {
        if (name != null && name.length() > 0 && name.charAt(0) == '@') {
            return attribute(name.substring(1));
        } else {
            return children(name);
        }
    }

    /**
     * 方括号语法的Groovy特定方法
     */
    public Object getAt(String name) {
        return getObject(name);
    }

    /**
     * 取节点名称
     */
    public String getName() {
        return nodeName;
    }

    public void setName(String name) {
        if (parentNode != null && parentNode.childrenByName != null) {
            parentNode.childrenByName.remove(name);
            parentNode.childrenByName.remove(nodeName);
        }
        nodeName = name;
    }

    /**
     * 取节点全部属性值
     */
    public Map<String, String> attributes() {
        return attributeMap;
    }

    /**
     * 按属性名称取节点属性值
     */
    public String attribute(String attrName) {
        String attrValue = attributeMap.get(attrName);
        if (systemExpandAttributes && attrValue != null && attrValue.contains("${")) {
            attrValue = SystemBinding.expand(attrValue);
            // 初始init完成后，系统属性和环境变量通常不会更改，因此保存扩展值
            attributeMap.put(attrName, attrValue);
        }
        if (attrValue != null && attrValue.isEmpty()) { attrValue = null; }
        return attrValue;
    }

    /**
     * 设置系统扩展属性
     */
    public void setSystemExpandAttributes(boolean b) {
        systemExpandAttributes = b;
    }

    public MNode getParent() {
        return parentNode;
    }

    public ArrayList<MNode> getChildren() {
        if (childList == null) childList = new ArrayList<>();
        return childList;
    }

    public ArrayList<MNode> children(String name) {
        if (childList == null) childList = new ArrayList<>();
        if (childrenByName == null) childrenByName = new HashMap<>();
        if (name == null) return childList;
        ArrayList<MNode> curList = childrenByName.get(name);
        if (curList != null) return curList;

        curList = new ArrayList<>();
        int childListSize = childList.size();
        for (MNode curChild : childList) {
            if (name.equals(curChild.nodeName)) curList.add(curChild);
        }
        childrenByName.put(name, curList);
        return curList;
    }

    public ArrayList<MNode> children(String name, String... attrNamesValues) {
        int attrNvLength = attrNamesValues.length;
        if (attrNvLength % 2 != 0)
            throw new IllegalArgumentException("XML解析错误: 必须传递偶数个属性名称/值字符串");
        ArrayList<MNode> fullList = children(name);
        ArrayList<MNode> filteredList = new ArrayList<>();
        int fullListSize = fullList.size();
        for (MNode node : fullList) {
            boolean allEqual = true;
            for (int j = 0; j < attrNvLength; j += 2) {
                String attrValue = node.attribute(attrNamesValues[j]);
                String argValue = attrNamesValues[j + 1];
                if (attrValue == null) {
                    if (argValue != null) {
                        allEqual = false;
                        break;
                    }
                } else {
                    if (!attrValue.equals(argValue)) {
                        allEqual = false;
                        break;
                    }
                }
            }
            if (allEqual) filteredList.add(node);
        }
        return filteredList;
    }

    public ArrayList<MNode> children(Closure<Boolean> condition) {
        ArrayList<MNode> curList = new ArrayList<>();
        if (childList == null) return curList;
        int childListSize = childList.size();
        for (MNode curChild : childList) {
            if (condition == null || condition.call(curChild)) curList.add(curChild);
        }
        return curList;
    }

    public boolean hasChild(String name) {
        if (childList == null) return false;
        if (name == null) return false;
        if (childrenByName != null) {
            ArrayList<MNode> curList = childrenByName.get(name);
            if (curList != null && curList.size() > 0) return true;
        }

        int childListSize = childList.size();
        for (MNode curChild : childList) {
            if (name.equals(curChild.nodeName)) return true;
        }
        return false;
    }

    /**
     * 获取索引处的子项，如果索引超出范围，将抛出异常
     */
    public MNode child(int index) {
        return childList.get(index);
    }

    public Map<String, ArrayList<MNode>> getChildrenByName() {
        Map<String, ArrayList<MNode>> allByName = new HashMap<>();
        if (childList == null) return allByName;
        int childListSize = childList.size();
        if (childListSize == 0) return allByName;
        if (childrenByName == null) childrenByName = new HashMap<>();

        ArrayList<String> newChildNames = new ArrayList<>();
        for (MNode curChild : childList) {
            String name = curChild.nodeName;
            ArrayList<MNode> existingList = childrenByName.get(name);
            if (existingList != null) {
                if (existingList.size() > 0 && !allByName.containsKey(name)) allByName.put(name, existingList);
                continue;
            }

            ArrayList<MNode> curList = allByName.get(name);
            if (curList == null) {
                curList = new ArrayList<>();
                allByName.put(name, curList);
                newChildNames.add(name);
            }
            curList.add(curChild);
        }
        // since we got all children by name save them for future use
        int newChildNamesSize = newChildNames.size();
        for (String newChildName : newChildNames) {
            childrenByName.put(newChildName, allByName.get(newChildName));
        }
        childrenByName.putAll(allByName);
        return allByName;
    }

    /**
     * 搜索所有后代以查找与任何名称匹配的节点，返回带有列表的Map，其中每个名称都包含找到的节点，如果没有找到节点则返回空List
     */
    public Map<String, ArrayList<MNode>> descendants(Set<String> names) {
        Map<String, ArrayList<MNode>> nodes = new HashMap<>();
        for (String name : names) nodes.put(name, new ArrayList<>());
        descendantsInternal(names, nodes);
        return nodes;
    }

    private void descendantsInternal(Set<String> names, Map<String, ArrayList<MNode>> nodes) {
        if (childList == null) return;

        int childListSize = childList.size();
        for (MNode curChild : childList) {
            if (names == null || names.contains(curChild.nodeName)) {
                ArrayList<MNode> curList = nodes.get(curChild.nodeName);
                curList.add(curChild);
            }
            curChild.descendantsInternal(names, nodes);
        }
    }

    public ArrayList<MNode> descendants(String name) {
        ArrayList<MNode> nodes = new ArrayList<>();
        descendantsInternal(name, nodes);
        return nodes;
    }

    private void descendantsInternal(String name, ArrayList<MNode> nodes) {
        if (childList == null) return;

        int childListSize = childList.size();
        for (MNode curChild : childList) {
            if (name == null || name.equals(curChild.nodeName)) {
                nodes.add(curChild);
            }
            curChild.descendantsInternal(name, nodes);
        }
    }

    public ArrayList<MNode> depthFirst(Closure<Boolean> condition) {
        ArrayList<MNode> curList = new ArrayList<>();
        depthFirstInternal(condition, curList);
        return curList;
    }

    private void depthFirstInternal(Closure<Boolean> condition, ArrayList<MNode> curList) {
        if (childList == null) return;

        int childListSize = childList.size();
        // 所有的孙子节点都是第一位的
        for (MNode curChild : childList) {
            curChild.depthFirstInternal(condition, curList);
        }
        // 那么孩子们
        for (MNode curChild : childList) {
            if (condition == null || condition.call(curChild)) curList.add(curChild);
        }
    }

    public ArrayList<MNode> breadthFirst(Closure<Boolean> condition) {
        ArrayList<MNode> curList = new ArrayList<>();
        breadthFirstInternal(condition, curList);
        return curList;
    }

    private void breadthFirstInternal(Closure<Boolean> condition, ArrayList<MNode> curList) {
        if (childList == null) return;

        int childListSize = childList.size();
        // 先是直接子节点
        for (MNode curChild : childList) {
            if (condition == null || condition.call(curChild)) curList.add(curChild);
        }
        // 之后孙子节点
        for (MNode curChild : childList) {
            curChild.breadthFirstInternal(condition, curList);
        }
    }

    /**
     * 获取第一个子节点
     */
    public MNode first() {
        if (childList == null) return null;
        return childList.size() > 0 ? childList.get(0) : null;
    }

    /**
     * 获取具有给定名称的第一个子节点
     */
    public MNode first(String name) {
        if (childList == null) return null;
        if (name == null) return first();

        ArrayList<MNode> nameChildren = children(name);
        if (nameChildren.size() > 0) return nameChildren.get(0);
        return null;

        /* with cache in children(name) that is faster than searching every time here:
        int childListSize = childList.size();
        for (int i = 0; i < childListSize; i++) {
            MNode curChild = childList.get(i);
            if (name.equals(curChild.nodeName)) return curChild;
        }
        return null;
        */
    }

    public MNode first(String name, String... attrNamesValues) {
        if (childList == null) return null;
        if (name == null) return first();

        ArrayList<MNode> nameChildren = children(name, attrNamesValues);
        if (nameChildren.size() > 0) return nameChildren.get(0);
        return null;
    }

    public MNode first(Closure<Boolean> condition) {
        if (childList == null) return null;
        if (condition == null) return first();
        int childListSize = childList.size();
        for (MNode curChild : childList) {
            if (condition.call(curChild)) return curChild;
        }
        return null;
    }

    public int firstIndex(String name) {
        if (childList == null) return -1;
        if (name == null) return childList.size() - 1;
        int childListSize = childList.size();
        for (int i = 0; i < childListSize; i++) {
            MNode curChild = childList.get(i);
            if (name.equals(curChild.getName())) return i;
        }
        return -1;
    }

    public int firstIndex(Closure<Boolean> condition) {
        if (childList == null) return -1;
        if (condition == null) return childList.size() - 1;
        int childListSize = childList.size();
        for (int i = 0; i < childListSize; i++) {
            MNode curChild = childList.get(i);
            if (condition.call(curChild)) return i;
        }
        return -1;
    }

    public String getText() {
        return childText;
    }

    public MNode deepCopy(MNode parent) {
        MNode newNode = new MNode(nodeName, attributeMap, parent, null, childText);
        if (childList != null) {
            int childListSize = childList.size();
            if (childListSize > 0) {
                newNode.childList = new ArrayList<>();
                for (int i = 0; i < childListSize; i++) {
                    MNode curChild = childList.get(i);
                    newNode.childList.add(curChild.deepCopy(newNode));
                }
            }
        }
        // if ("entity".equals(nodeName)) logger.info("Original MNode:\n" + this.toString() + "\n Clone MNode:\n" + newNode.toString());
        return newNode;
    }

    /* ========== 子节点修改方法 ========== */

    public void append(MNode child) {
        if (childrenByName != null) childrenByName.remove(child.nodeName);
        if (childList == null) childList = new ArrayList<>();
        childList.add(child);
        child.parentNode = this;
    }

    public void append(MNode child, int index) {
        if (childrenByName != null) childrenByName.remove(child.nodeName);
        if (childList == null) childList = new ArrayList<>();
        if (index > childList.size()) index = childList.size();
        childList.add(index, child);
        child.parentNode = this;
    }

    public MNode append(Node child) {
        MNode newNode = new MNode(child);
        append(newNode);
        return newNode;
    }

    public MNode append(String name, Map<String, String> attributes, List<MNode> children, String text) {
        MNode newNode = new MNode(name, attributes, this, children, text);
        append(newNode);
        return newNode;
    }

    public MNode append(String name, Map<String, String> attributes) {
        MNode newNode = new MNode(name, attributes, this, null, null);
        append(newNode);
        return newNode;
    }

    public MNode replace(int index, MNode child) {
        if (childList == null || childList.size() < index)
            throw new IllegalArgumentException("XML解析错误: 索引 [" + index + "] 无效, 子节点数量为: " + (childList == null ? 0 : childList.size()));
        return childList.set(index, child);
    }

    public MNode replace(int index, String name, Map<String, String> attributes) {
        if (childList == null || childList.size() < index)
            throw new IllegalArgumentException("XML解析错误: 索引 [" + index + "] 无效, 子节点数量为: " + (childList == null ? 0 : childList.size()));
        MNode newNode = new MNode(name, attributes, this, null, null);
        childList.set(index, newNode);
        return newNode;
    }

    public void remove(int index) {
        if (childList == null || childList.size() < index)
            throw new IllegalArgumentException("XML解析错误: 索引 [" + index + "] 无效, 子节点数量为: " + (childList == null ? 0 : childList.size()));
        childList.remove(index);
    }

    public boolean remove(String name) {
        if (childrenByName != null) childrenByName.remove(name);
        if (childList == null) return false;
        boolean removed = false;
        for (int i = 0; i < childList.size(); ) {
            MNode curChild = childList.get(i);
            if (curChild.nodeName.equals(name)) {
                childList.remove(i);
                removed = true;
            } else {
                i++;
            }
        }
        return removed;
    }

    public boolean remove(Closure<Boolean> condition) {
        if (childList == null) return false;
        boolean removed = false;
        for (int i = 0; i < childList.size(); ) {
            MNode curChild = childList.get(i);
            if (condition.call(curChild)) {
                if (childrenByName != null) childrenByName.remove(curChild.nodeName);
                childList.remove(i);
                removed = true;
            } else {
                i++;
            }
        }
        return removed;
    }

    /**
     * 如果具有该名称的子项，则使用overrideNode合并具有给定名称的单个子节点。
     * 如果此节点具有相同名称的子节点，则从overrideNode的子节点复制/覆盖属性，并且如果overrideNode的子节点具有子节点，则此节点的子节点的子节点将被它们替换。
     * 否则，将override子项的副本作为当前节点的子项附加。
     */
    public void mergeSingleChild(MNode overrideNode, String childNodeName) {
        MNode childOverrideNode = overrideNode.first(childNodeName);
        if (childOverrideNode == null) return;
        MNode childBaseNode = first(childNodeName);
        if (childBaseNode != null) {
            childBaseNode.attributeMap.putAll(childOverrideNode.attributeMap);
            if (childOverrideNode.childList != null && childOverrideNode.childList.size() > 0) {
                ArrayList<MNode> conChildList =mergeNode(childOverrideNode, childBaseNode);
                int conChildListSize = conChildList.size();
                for (MNode grandchild : conChildList) {
                    childBaseNode.childList.add(grandchild.deepCopy(childBaseNode));
                }
            }
        } else {
            if (childrenByName != null) childrenByName.remove(childOverrideNode.nodeName);
            if (childList == null) childList = new ArrayList<>();
            childList.add(childOverrideNode.deepCopy(this));
        }
    }

    public void mergeChildWithChildKey(MNode overrideNode, String childName, String grandchildName, String keyAttributeName, Closure grandchildMerger) {
        MNode overrideChildNode = overrideNode.first(childName);
        if (overrideChildNode == null) return;
        MNode baseChildNode = first(childName);
        if (baseChildNode != null) {
            baseChildNode.mergeNodeWithChildKey(overrideChildNode, grandchildName, keyAttributeName, grandchildMerger);
        } else {
            if (childrenByName != null) childrenByName.remove(overrideChildNode.nodeName);
            if (childList == null) childList = new ArrayList<>();
            childList.add(overrideChildNode.deepCopy(this));
        }
    }

    /**
     * 将来自overrideNode的属性和子节点合并到此节点中，匹配childNodesName，
     * 并可选地匹配keyAttributeName指定的每个属性的值。
     * 始终从覆盖子节点复制/覆盖属性，并使用childMerger合并其子节点，
     * 如果为null，则删除此节点子节点下的所有子节点的默认合并，并附加覆盖子节点的子节点的副本。
     */
    public void mergeNodeWithChildKey(MNode overrideNode, String childNodesName, String keyAttributeName, Closure childMerger) {
        if (overrideNode == null) throw new IllegalArgumentException("XML解析错误: 在调用 mergeNodeWithChildKey 方法时未指定 overrideNode (覆盖节点)");
        if (childNodesName == null || childNodesName.length() == 0) throw new IllegalArgumentException("XML解析错误: 在调用mergeNodeWithChildKey 方法时未指定childNodesName(子节点名称)");

        // 覆盖此节点的属性
        attributeMap.putAll(overrideNode.attributeMap);

        mergeChildrenByKey(overrideNode, childNodesName, keyAttributeName, childMerger);
    }

    public void mergeChildrenByKey(MNode overrideNode, String childNodesName, String keyAttributeName, Closure childMerger) {
        if (keyAttributeName == null || keyAttributeName.isEmpty()) {
            mergeChildrenByKeys(overrideNode, childNodesName, childMerger);
        } else {
            mergeChildrenByKeys(overrideNode, childNodesName, childMerger, keyAttributeName);
        }
    }

    public void mergeChildrenByKeys(MNode overrideNode, String childNodesName, Closure childMerger, String... keyAttributeNames) {
        if (overrideNode == null) throw new IllegalArgumentException("XML解析错误: 在调用 mergeNodeWithChildKey 方法时未指定 overrideNode (覆盖节点)");
        if (childNodesName == null || childNodesName.length() == 0) throw new IllegalArgumentException("XML解析错误: 在调用mergeNodeWithChildKey 方法时未指定childNodesName(子节点名称)");

        if (childList == null) childList = new ArrayList<>();
        ArrayList<MNode> overrideChildren = overrideNode.children(childNodesName);
        for (MNode childOverrideNode : overrideChildren) {
            String[] keyAttributeValues = null;
            if (keyAttributeNames != null && keyAttributeNames.length > 0) {
                keyAttributeValues = new String[keyAttributeNames.length];
                boolean skipChild = false;
                for (int ai = 0; ai < keyAttributeNames.length; ai++) {
                    String keyValue = childOverrideNode.attribute(keyAttributeNames[ai]);
                    // 如果我们有一个键属性名但没有该子节点的键值，跳过它
                    if (keyValue == null || keyValue.length() == 0) {
                        skipChild = true;
                        continue;
                    }
                    keyAttributeValues[ai] = keyValue;
                }
                if (skipChild) continue;
            }

            MNode childBaseNode = null;
            for (MNode curChild : childList) {
                if (!curChild.getName().equals(childNodesName)) continue;
                if (keyAttributeNames == null || keyAttributeNames.length == 0) {
                    childBaseNode = curChild;
                    break;
                }
                boolean allMatch = true;
                for (int ai = 0; ai < keyAttributeNames.length; ai++) {
                    String keyValue = keyAttributeValues[ai];
                    if (!keyValue.equals(curChild.attribute(keyAttributeNames[ai]))) allMatch = false;
                }
                if (allMatch) {
                    childBaseNode = curChild;
                    break;
                }
            }

            if (childBaseNode != null) {
                // 合并节点属性
                childBaseNode.attributeMap.putAll(childOverrideNode.attributeMap);

                if (childMerger != null) {
                    childMerger.call(childBaseNode, childOverrideNode);
                } else {
                    // 执行默认子项合并：删除当前节点子项并替换为覆盖节点的子项的副本
                    ArrayList<MNode> conChildList = mergeNode(childOverrideNode, childBaseNode);
                    if (conChildList != null) for (MNode grandchild : conChildList)
                        childBaseNode.childList.add(grandchild.deepCopy(childBaseNode));
                }
            } else {
                // 没有匹配的子基节点，所以添加一个新节点
                append(childOverrideNode.deepCopy(this));
            }
        }
    }

    private ArrayList<MNode> mergeNode(MNode childOverrideNode, MNode childBaseNode) {
        if (childBaseNode.childList != null) {
            if (childBaseNode.childrenByName != null) childBaseNode.childrenByName.clear();
            childBaseNode.childList.clear();
        } else {
            childBaseNode.childList = new ArrayList<>();
        }
        return childOverrideNode.childList;
    }

    /* ========== String Methods ========== */

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        addToSb(sb, 0);
        return sb.toString();
    }

    private void addToSb(StringBuilder sb, int level) {
        for (int i = 0; i < level; i++) sb.append("    ");
        sb.append('<').append(nodeName);
        for (Map.Entry<String, String> entry : attributeMap.entrySet())
            sb.append(' ').append(entry.getKey()).append("=\"").append(entry.getValue()).append("\"");
        if ((childText != null && childText.length() > 0) || (childList != null && childList.size() > 0)) {
            sb.append(">");
            if (childText != null) sb.append("<![CDATA[").append(childText).append("]]>");
            if (childList != null && childList.size() > 0) {
                for (MNode child : childList) {
                    sb.append('\n');
                    child.addToSb(sb, level + 1);
                }
                if (childList.size() > 1) {
                    sb.append("\n");
                    for (int i = 0; i < level; i++) sb.append("    ");
                }
            }

            sb.append("</").append(nodeName).append('>');
        } else {
            sb.append("/>");
        }
    }

    private static String gnodeText(Object nodeObj) {
        if (nodeObj == null) return "";
        Node theNode = null;
        if (nodeObj instanceof Node) {
            theNode = (Node) nodeObj;
        } else if (nodeObj instanceof NodeList) {
            NodeList nl = (NodeList) nodeObj;
            if (nl.size() > 0) theNode = (Node) nl.get(0);
        }
        if (theNode == null) return "";
        List<String> textList = theNode.localText();
        if (textList != null) {
            if (textList.size() == 1) {
                return textList.get(0);
            } else {
                StringBuilder sb = new StringBuilder();
                for (String txt : textList) sb.append(txt).append("\n");
                return sb.toString();
            }
        } else {
            return "";
        }
    }

    private static class MNodeXmlHandler extends DefaultHandler {
        Locator locator = null;
        long nodesRead = 0;

        MNode rootNode = null;
        MNode curNode = null;
        StringBuilder curText = null;

        final boolean rootOnly;
        private boolean stopParse = false;

        MNodeXmlHandler(boolean rootOnly) {
            this.rootOnly = rootOnly;
        }

        MNode getRootNode() {
            return rootNode;
        }

        long getNodesRead() {
            return nodesRead;
        }

        @Override
        public void startElement(String ns, String localName, String qName, Attributes attributes) {
            if (stopParse) return;

            // logger.info("startElement ns [${ns}], localName [${localName}] qName [${qName}]")
            if (curNode == null) {
                curNode = new MNode(qName, null);
                if (rootNode == null) rootNode = curNode;
            } else {
                curNode = curNode.append(qName, null);
            }

            int length = attributes.getLength();
            for (int i = 0; i < length; i++) {
                String name = attributes.getLocalName(i);
                String value = attributes.getValue(i);
                if (name == null || name.length() == 0) name = attributes.getQName(i);
                curNode.attributeMap.put(name, value);
            }

            if (rootOnly) stopParse = true;
        }

        @Override
        public void characters(char[] chars, int offset, int length) {
            if (stopParse) return;

            if (curText == null) curText = new StringBuilder();
            curText.append(chars, offset, length);
        }

        @Override
        public void endElement(String ns, String localName, String qName) {
            if (stopParse) return;

            if (!qName.equals(curNode.nodeName)) throw new IllegalStateException("Invalid close element " + qName + ", was expecting " + curNode.nodeName);
            if (curText != null) {
                String curString = curText.toString().trim();
                if (curString.length() > 0) curNode.childText = curString;
            }
            curNode = curNode.parentNode;
            curText = null;
        }

        @Override
        public void setDocumentLocator(Locator locator) { this.locator = locator; }
    }

    /* ============================================================== */
    /* ========== FreeMarker (FTL) Fields Methods, Classes ========== */
    /* ============================================================== */

    private static final BeansWrapper wrapper = new BeansWrapperBuilder(FTL_VERSION).build();
    private static final FtlNodeListWrapper emptyNodeListWrapper = new FtlNodeListWrapper(new ArrayList<>(), null);
    private FtlNodeListWrapper allChildren = null;
    private ConcurrentHashMap<String, TemplateModel> ftlAttrAndChildren = null;
    private ConcurrentHashMap<String, Boolean> knownNullAttributes = null;

    public Object getAdaptedObject(Class aClass) { return this; }

    // TemplateHashModel methods
    @Override public TemplateModel get(String s) {
        if (s == null) return null;
        // first try the attribute and children caches, then if not found in either pick it apart and create what is needed
        ConcurrentHashMap<String, TemplateModel> localAttrAndChildren = ftlAttrAndChildren != null ? ftlAttrAndChildren : makeAttrAndChildrenByName();
        TemplateModel attrOrChildWrapper = localAttrAndChildren.get(s);
        if (attrOrChildWrapper != null) return attrOrChildWrapper;
        if (knownNullAttributes != null && knownNullAttributes.containsKey(s)) return null;

        // at this point we got a null value but attributes and child nodes were pre-loaded so return null or empty list
        if (s.startsWith("@")) {
            if ("@@text".equals(s)) {
                // if we got this once will get it again so add @@text always, always want wrapper though may be null
                FtlTextWrapper textWrapper = new FtlTextWrapper(childText, this);
                localAttrAndChildren.putIfAbsent("@@text", textWrapper);
                return localAttrAndChildren.get("@@text");
                // TODO: handle other special hashes? (see http://www.freemarker.org/docs/xgui_imperative_formal.html)
            } else {
                String attrName = s.substring(1);
                String attrValue = attributeMap.get(attrName);
                if (attrValue == null) {
                    if (knownNullAttributes == null) knownNullAttributes = new ConcurrentHashMap<>();
                    knownNullAttributes.put(s, Boolean.TRUE);
                    return null;
                } else {
                    FtlAttributeWrapper attrWrapper = new FtlAttributeWrapper(attrName, attrValue, this);
                    TemplateModel existingAttr = localAttrAndChildren.putIfAbsent(s, attrWrapper);
                    if (existingAttr != null) return existingAttr;
                    return attrWrapper;
                }
            }
        } else {
            if (hasChild(s)) {
                FtlNodeListWrapper nodeListWrapper = new FtlNodeListWrapper(children(s), this);
                TemplateModel existingNodeList = localAttrAndChildren.putIfAbsent(s, nodeListWrapper);
                if (existingNodeList != null) return existingNodeList;
                return nodeListWrapper;
            } else {
                return emptyNodeListWrapper;
            }
        }
    }
    private synchronized ConcurrentHashMap<String, TemplateModel> makeAttrAndChildrenByName() {
        if (ftlAttrAndChildren == null) ftlAttrAndChildren = new ConcurrentHashMap<>();
        return ftlAttrAndChildren;
    }
    @Override public boolean isEmpty() {
        return attributeMap.isEmpty() && (childList == null || childList.isEmpty()) && (childText == null || childText.length() == 0);
    }

    // TemplateHashModelEx methods
    @Override public TemplateCollectionModel keys() throws TemplateModelException { return new SimpleCollection(attributeMap.keySet(), wrapper); }
    @Override public TemplateCollectionModel values() throws TemplateModelException { return new SimpleCollection(attributeMap.values(), wrapper); }

    // TemplateNodeModel methods
    @Override public TemplateNodeModel getParentNode() { return parentNode; }
    @Override public TemplateSequenceModel getChildNodes() { return this; }
    @Override public String getNodeName() { return getName(); }
    @Override public String getNodeType() { return "element"; }
    @Override public String getNodeNamespace() { return null; } /* Namespace not supported for now. */

    // TemplateSequenceModel methods
    @Override public TemplateModel get(int i) {
        if (allChildren == null) return getSequenceList().get(i);
        return allChildren.get(i);
    }
    @Override public int size() {
        if (allChildren == null) return getSequenceList().size();
        return allChildren.size();
    }
    private FtlNodeListWrapper getSequenceList() {
        // Looks like attributes should NOT go in the FTL children list, so just use the node.children()
        if (allChildren == null) allChildren = (childText != null && childText.length() > 0) ?
                new FtlNodeListWrapper(childText, this) : new FtlNodeListWrapper(childList, this);
        return allChildren;
    }

    // TemplateScalarModel methods
    @Override public String getAsString() { return childText != null ? childText : ""; }

    private static class FtlAttributeWrapper implements TemplateNodeModel, TemplateSequenceModel, AdapterTemplateModel,
            TemplateScalarModel {
        protected String key;
        protected String value;
        MNode parentNode;
        FtlAttributeWrapper(String key, String value, MNode parentNode) {
            this.key = key;
            this.value = value;
            this.parentNode = parentNode;
        }

        @Override public Object getAdaptedObject(Class aClass) { return value; }

        // TemplateNodeModel methods
        @Override public TemplateNodeModel getParentNode() { return parentNode; }
        @Override public TemplateSequenceModel getChildNodes() { return this; }
        @Override public String getNodeName() { return key; }
        @Override public String getNodeType() { return "attribute"; }
        @Override public String getNodeNamespace() { return null; } /* Namespace not supported for now. */

        // TemplateSequenceModel methods
        @Override public TemplateModel get(int i) {
            if (i == 0) try {
                return wrapper.wrap(value);
            } catch (TemplateModelException e) {
                throw new BaseException("Error wrapping object for FreeMarker", e);
            }
            throw new IndexOutOfBoundsException("Attribute node only has 1 value. Tried to get index [${i}] for attribute [${key}]");
        }
        @Override public int size() { return 1; }

        // TemplateScalarModel methods
        @Override public String getAsString() { return value; }
        @Override public String toString() { return value; }
    }

    private static class FtlTextWrapper implements TemplateNodeModel, TemplateSequenceModel, AdapterTemplateModel, TemplateScalarModel {
        protected String text;
        MNode parentNode;
        FtlTextWrapper(String text, MNode parentNode) {
            this.text = text;
            this.parentNode = parentNode;
        }

        @Override public Object getAdaptedObject(Class aClass) { return text; }

        // TemplateNodeModel methods
        @Override public TemplateNodeModel getParentNode() { return parentNode; }
        @Override public TemplateSequenceModel getChildNodes() { return this; }
        @Override public String getNodeName() { return "@text"; }
        @Override public String getNodeType() { return "text"; }
        @Override public String getNodeNamespace() { return null; } /* Namespace not supported for now. */

        // TemplateSequenceModel methods
        @Override public TemplateModel get(int i) {
            if (i == 0) try {
                return wrapper.wrap(getAsString());
            } catch (TemplateModelException e) {
                throw new BaseException("Error wrapping object for FreeMarker", e);
            }
            throw new IndexOutOfBoundsException("Text node only has 1 value. Tried to get index [${i}]");
        }
        @Override public int size() { return 1; }

        // TemplateScalarModel methods
        @Override public String getAsString() { return text != null ? text : ""; }
        @Override public String toString() { return getAsString(); }
    }

    private static class FtlNodeListWrapper implements TemplateSequenceModel {
        ArrayList<TemplateModel> nodeList = new ArrayList<>();
        FtlNodeListWrapper(ArrayList<MNode> mnodeList, MNode parentNode) {
            if (mnodeList != null) nodeList.addAll(mnodeList);
        }
        FtlNodeListWrapper(String text, MNode parentNode) {
            nodeList.add(new FtlTextWrapper(text, parentNode));
        }

        @Override public TemplateModel get(int i) { return nodeList.get(i); }
        @Override public int size() { return nodeList.size(); }
        @Override public String toString() { return nodeList.toString(); }
    }
}
