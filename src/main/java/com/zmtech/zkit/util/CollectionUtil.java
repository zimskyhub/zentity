package com.zmtech.zkit.util;

import groovy.util.Node;
import groovy.util.NodeList;
import org.codehaus.groovy.runtime.DefaultGroovyMethods;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.*;

/**
 * 这些实用程序应该在别的地方有，但我找不到一个好的简单库，它们都太蠢了，但这东西还很必要。
 */
@SuppressWarnings("unused")
public class CollectionUtil {
    protected static final Logger logger = LoggerFactory.getLogger(CollectionUtil.class);

    public static class KeyValue {
        public String key;
        public Object value;

        public KeyValue(String key, Object value) {
            this.key = key;
            this.value = value;
        }
    }

    public static void filterMapList(List<Map> theList, Map<String, Object> fieldValues) {
        filterMapList(theList, fieldValues, false);
    }

    /**
     * 使用字段值过滤列表（地图）; if exclude = true删除匹配项，否则只保留匹配项
     */
    public static void filterMapList(List<Map> theList, Map<String, Object> fieldValues, boolean exclude) {
        if (theList == null || fieldValues == null) return;
        int listSize = theList.size();
        if (listSize == 0) return;
        int numFields = fieldValues.size();
        if (numFields == 0) return;

        String[] fieldNameArray = new String[numFields];
        Object[] fieldValueArray = new Object[numFields];

        int index = 0;
        for (Map.Entry<String, Object> entry : fieldValues.entrySet()) {
            fieldNameArray[index] = entry.getKey();
            fieldValueArray[index] = entry.getValue();
            index++;
        }

        if (theList instanceof RandomAccess) {
            for (int li = 0; li < listSize; ) {
                Map curMap = theList.get(li);
                if (checkRemove(curMap, fieldNameArray, fieldValueArray, numFields, exclude)) {
                    theList.remove(li);
                    listSize--;
                } else {
                    li++;
                }
            }
        } else {
            theList.removeIf(curMap -> checkRemove(curMap, fieldNameArray, fieldValueArray, numFields, exclude));
        }
    }

    private static boolean checkRemove(Map curMap, String[] fieldNameArray, Object[] fieldValueArray, int numFields, boolean exclude) {
        boolean remove = exclude;
        for (int i = 0; i < numFields; i++) {
            String fieldName = fieldNameArray[i];
            Object compareObj = fieldValueArray[i];
            Object curObj = curMap.get(fieldName);
            if (compareObj == null) {
                if (curObj != null) {
                    remove = !exclude;
                    break;
                }
            } else {
                if (!compareObj.equals(curObj)) {
                    remove = !exclude;
                    break;
                }
            }
        }
        return remove;
    }

    public static List<Map> filterMapListByDate(List<Map> theList, String fromDateName, String thruDateName, Timestamp compareStamp) {
        if (theList == null || theList.size() == 0) return theList;

        if (fromDateName == null || fromDateName.isEmpty()) fromDateName = "fromDate";
        if (thruDateName == null || thruDateName.isEmpty()) thruDateName = "thruDate";
        // 这里不能访问访问ec.user，所以应该总是传入，但以防万一
        if (compareStamp == null) compareStamp = new Timestamp(System.currentTimeMillis());

        Iterator<Map> theIterator = theList.iterator();
        while (theIterator.hasNext()) {
            Map curMap = theIterator.next();
            Timestamp fromDate = DefaultGroovyMethods.asType(curMap.get(fromDateName), Timestamp.class);
            if (fromDate != null && compareStamp.compareTo(fromDate) < 0) {
                theIterator.remove();
                continue;
            }
            Timestamp thruDate = DefaultGroovyMethods.asType(curMap.get(thruDateName), Timestamp.class);
            if (thruDate != null && compareStamp.compareTo(thruDate) >= 0) theIterator.remove();
        }
        return theList;
    }

    public static void filterMapListByDate(List<Map> theList, String fromDateName, String thruDateName, Timestamp compareStamp, boolean ignoreIfEmpty) {
        if (ignoreIfEmpty && compareStamp == null) return;
        filterMapListByDate(theList, fromDateName, thruDateName, compareStamp);
    }

    /**
     * 适当的订单列表元素（修改传入的列表），为方便起见返回列表
     */
    public static List<Map<String, Object>> orderMapList(List<Map<String, Object>> theList, List<? extends CharSequence> fieldNames) {
        if (fieldNames == null)
            throw new IllegalArgumentException("无法按字段列表的顺序排序Map列表!");
        if (theList != null && fieldNames.size() > 0) theList.sort(new MapOrderByComparator(fieldNames));
        return theList;
    }

    public static class MapOrderByComparator implements Comparator<Map> {
        String[] fieldNameArray;

        public MapOrderByComparator(List<? extends CharSequence> fieldNameList) {
            ArrayList<String> fieldArrayList = new ArrayList<>();
            for (CharSequence fieldName : fieldNameList) {
                String fieldStr = fieldName.toString();
                if (fieldStr.contains(",")) {
                    String[] curFieldArray = fieldStr.split(",");
                    for (String curField : curFieldArray) {
                        if (curField == null) continue;
                        fieldArrayList.add(curField.trim());
                    }
                } else {
                    fieldArrayList.add(fieldStr);
                }
            }
            fieldNameArray = fieldArrayList.toArray(new String[0]);
            // logger.warn("Order list by " + Arrays.asList(fieldNameArray));
        }

        @SuppressWarnings("unchecked")
        @Override
        public int compare(Map map1, Map map2) {
            if (map1 == null) return -1;
            if (map2 == null) return 1;
            for (String s : fieldNameArray) {
                String fieldName = s;
                boolean ascending = true;
                boolean ignoreCase = false;
                if (fieldName.charAt(0) == '-') {
                    ascending = false;
                    fieldName = fieldName.substring(1);
                } else if (fieldName.charAt(0) == '+') {
                    fieldName = fieldName.substring(1);
                }
                if (fieldName.charAt(0) == '^') {
                    ignoreCase = true;
                    fieldName = fieldName.substring(1);
                }
                Comparable value1 = (Comparable) map1.get(fieldName);
                Comparable value2 = (Comparable) map2.get(fieldName);
                // 注意：空值在列表中较早的位置用于升序，稍后在列表中用于升序
                if (value1 == null) {
                    if (value2 != null) return ascending ? 1 : -1;
                } else {
                    if (value2 == null) {
                        return ascending ? -1 : 1;
                    } else {
                        if (ignoreCase && value1 instanceof String && value2 instanceof String) {
                            int comp = ((String) value1).compareToIgnoreCase((String) value2);
                            if (comp != 0) return ascending ? comp : -comp;
                        } else {
                            if (value1.getClass() != value2.getClass()) {
                                if (value1 instanceof Number && value2 instanceof Number) {
                                    value1 = new BigDecimal(value1.toString());
                                    value2 = new BigDecimal(value2.toString());
                                }
                                // 注意：任何其他类型规范化以避免compareTo（）转换异常？
                            }
                            int comp = value1.compareTo(value2);
                            if (comp != 0) return ascending ? comp : -comp;
                        }
                    }
                }
            }
            // all结果为0，所以是相同的，所以返回0
            return 0;
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof MapOrderByComparator && Arrays.equals(fieldNameArray, ((MapOrderByComparator) obj).fieldNameArray);
        }

        @Override
        public String toString() {
            return Arrays.toString(fieldNameArray);
        }
    }

    /**
     * 对于Map列表，找到与fieldsByPriority Ordered Map最匹配的条目;
     * mapList中Map中的空字段值与任何值匹配，但不会对最大匹配分数有所贡献，否则fieldsByPriority中每个字段的值必须匹配才能成为候选。
     */
    public static Map<String, Object> findMaximalMatch(List<Map<String, Object>> mapList, LinkedHashMap<String, Object> fieldsByPriority) {
        int numFields = fieldsByPriority.size();
        String[] fieldNames = new String[numFields];
        Object[] fieldValues = new Object[numFields];
        int index = 0;
        for (Map.Entry<String, Object> entry : fieldsByPriority.entrySet()) {
            fieldNames[index] = entry.getKey();
            fieldValues[index] = entry.getValue();
            index++;
        }

        int highScore = -1;
        Map<String, Object> highMap = null;
        for (Map<String, Object> curMap : mapList) {
            int curScore = 0;
            boolean skipMap = false;
            for (int i = 0; i < numFields; i++) {
                String curField = fieldNames[i];
                Object compareValue = fieldValues[i];
                // 如果curMap值为null跳过字段（Map中的空值表示允许任何匹配值
                Object curValue = curMap.get(curField);
                if (curValue == null) continue;
                // if not equal skip Map
                if (!curValue.equals(compareValue)) {
                    skipMap = true;
                    break;
                }
                // 添加到基于索引的分数（较低的索引较高分数），也添加numFields，以便更多的字段匹配权重更高
                curScore += (numFields - i) + numFields;
            }

            if (skipMap) continue;
            // 有更高的？
            if (curScore > highScore) {
                highScore = curScore;
                highMap = curMap;
            }
        }

        return highMap;
    }

    @SuppressWarnings("unchecked")
    public static void addToListInMap(Object key, Object value, Map theMap) {
        if (theMap == null) return;

        List theList = (List) theMap.get(key);
        if (theList == null) {
            theList = new ArrayList();
            theMap.put(key, theList);
        }
        theList.add(value);
    }

    @SuppressWarnings("unchecked")
    public static boolean addToSetInMap(Object key, Object value, Map theMap) {
        if (theMap == null) return false;
        Set theSet = (Set) theMap.get(key);
        if (theSet == null) {
            theSet = new LinkedHashSet();
            theMap.put(key, theSet);
        }
        return theSet.add(value);
    }

    @SuppressWarnings("unchecked")
    public static void addToMapInMap(Object keyOuter, Object keyInner, Object value, Map theMap) {
        if (theMap == null) return;
        Map innerMap = (Map) theMap.get(keyOuter);
        if (innerMap == null) {
            innerMap = new LinkedHashMap();
            theMap.put(keyOuter, innerMap);
        }
        innerMap.put(keyInner, value);
    }

    @SuppressWarnings("unchecked")
    public static void addToBigDecimalInMap(Object key, BigDecimal value, Map theMap) {
        if (value == null || theMap == null) return;
        Object curObj = theMap.get(key);
        if (curObj == null) {
            theMap.put(key, value);
        } else {
            BigDecimal curVal;
            if (curObj instanceof BigDecimal) curVal = (BigDecimal) curObj;
            else curVal = new BigDecimal(curObj.toString());
            theMap.put(key, curVal.add(value));
        }
    }

    public static void addBigDecimalsInMap(Map<String, Object> baseMap, Map<String, Object> addMap) {
        if (baseMap == null || addMap == null) return;
        for (Map.Entry<String, Object> entry : addMap.entrySet()) {
            if (!(entry.getValue() instanceof BigDecimal)) continue;
            BigDecimal addVal = (BigDecimal) entry.getValue();
            Object baseObj = baseMap.get(entry.getKey());
            if (baseObj == null || !(baseObj instanceof BigDecimal)) baseObj = BigDecimal.ZERO;
            BigDecimal baseVal = (BigDecimal) baseObj;
            baseMap.put(entry.getKey(), baseVal.add(addVal));
        }
    }

    public static void divideBigDecimalsInMap(Map<String, Object> baseMap, BigDecimal divisor) {
        if (baseMap == null || divisor == null || divisor.doubleValue() == 0.0) return;
        for (Map.Entry<String, Object> entry : baseMap.entrySet()) {
            if (!(entry.getValue() instanceof BigDecimal)) continue;
            BigDecimal baseVal = (BigDecimal) entry.getValue();
            entry.setValue(baseVal.divide(divisor, BigDecimal.ROUND_HALF_UP));
        }
    }

    /**
     * 返回带有total，squaredTotal，count，average，stdDev，maximum的Map;
     * Maps中的fieldName字段必须具有BigDecimal类型;
     * 如果非空字段的计数小于2，则返回null，因为无法计算标准差
     */
    public static Map<String, BigDecimal> stdDevMaxFromMapField(List<Map<String, Object>> dataList, String fieldName, BigDecimal stdDevMultiplier) {
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal squaredTotal = BigDecimal.ZERO;
        int count = 0;
        for (Map<String, Object> dataMap : dataList) {
            if (dataMap == null) continue;
            BigDecimal value = (BigDecimal) dataMap.get(fieldName);
            if (value == null) continue;
            total = total.add(value);
            squaredTotal = squaredTotal.add(value.multiply(value));
            count++;
        }
        if (count < 2) return null;

        BigDecimal countBd = new BigDecimal(count);
        BigDecimal average = total.divide(countBd, BigDecimal.ROUND_HALF_UP);
        double totalDouble = total.doubleValue();
        BigDecimal stdDev = new BigDecimal(Math.sqrt(Math.abs(squaredTotal.doubleValue() - ((totalDouble * totalDouble) / count)) / (count - 1)));

        Map<String, BigDecimal> retMap = new HashMap<>(6);
        retMap.put("total", total);
        retMap.put("squaredTotal", squaredTotal);
        retMap.put("count", countBd);
        retMap.put("average", average);
        retMap.put("stdDev", stdDev);

        if (stdDevMultiplier != null) retMap.put("maximum", average.add(stdDev.multiply(stdDevMultiplier)));

        return retMap;
    }

    /**
     * 在包含字段，地图和地图集合（列表等）的嵌套地图中查找字段值
     */
    public static Object findFieldNestedMap(String key, Map theMap) {
        if (theMap.containsKey(key)) return theMap.get(key);
        for (Object value : theMap.values()) {
            if (value instanceof Map) {
                Object fieldValue = findFieldNestedMap(key, (Map) value);
                if (fieldValue != null) return fieldValue;
            } else if (value instanceof Collection) {
                // only look in Collections of Maps
                for (Object colValue : (Collection) value) {
                    if (colValue instanceof Map) {
                        Object fieldValue = findFieldNestedMap(key, (Map) colValue);
                        if (fieldValue != null) return fieldValue;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 在包含字段，地图和地图集合（列表等）的嵌套地图中查找命名字段的所有值
     */
    public static void findAllFieldsNestedMap(String key, Map theMap, Set<Object> valueSet) {
        Object localValue = theMap.get(key);
        if (localValue != null) valueSet.add(localValue);
        for (Object value : theMap.values()) {
            if (value instanceof Map) {
                findAllFieldsNestedMap(key, (Map) value, valueSet);
            } else if (value instanceof Collection) {
                // only look in Collections of Maps
                for (Object colValue : (Collection) value) {
                    if (colValue instanceof Map) findAllFieldsNestedMap(key, (Map) colValue, valueSet);
                }
            }
        }
    }

    /**
     * 在包含字段，地图和地图集合（列表等）的嵌套地图中查找命名字段的所有值
     */
    @SuppressWarnings("unchecked")
    public static Map flattenNestedMap(Map theMap) {
        if (theMap == null) return null;
        Map outMap = new LinkedHashMap();
        for (Object entryObj : theMap.entrySet()) {
            Map.Entry entry = (Map.Entry) entryObj;
            Object value = entry.getValue();
            if (value instanceof Map) {
                outMap.putAll(flattenNestedMap((Map) value));
            } else if (value instanceof Collection) {
                for (Object colValue : (Collection) value) {
                    if (colValue instanceof Map) outMap.putAll(flattenNestedMap((Map) colValue));
                }
            } else {
                outMap.put(entry.getKey(), entry.getValue());
            }
        }
        return outMap;
    }

    @SuppressWarnings("unchecked")
    public static void mergeNestedMap(Map<Object, Object> baseMap, Map<Object, Object> overrideMap, boolean overrideEmpty) {
        if (baseMap == null || overrideMap == null) return;
        for (Map.Entry<Object, Object> entry : overrideMap.entrySet()) {
            Object key = entry.getKey();
            Object value = entry.getValue();
            if (baseMap.containsKey(key)) {
                if (value == null) {
                    if (overrideEmpty) baseMap.put(key, null);
                } else {
                    if (value instanceof CharSequence) {
                        if (overrideEmpty || ((CharSequence) value).length() > 0) baseMap.put(key, value);
                    } else if (value instanceof Map) {
                        Object baseValue = baseMap.get(key);
                        if (baseValue instanceof Map) {
                            mergeNestedMap((Map) baseValue, (Map) value, overrideEmpty);
                        } else {
                            baseMap.put(key, value);
                        }
                    } else if (value instanceof Collection) {
                        Object baseValue = baseMap.get(key);
                        if (baseValue instanceof Collection) {
                            Collection baseCol = (Collection) baseValue;
                            Collection overrideCol = (Collection) value;
                            for (Object overrideObj : overrideCol) {
                                // NOTE: if we have a Collection of Map we have no way to merge the Maps without knowing the 'key' entries to use to match them
                                if (!baseCol.contains(overrideObj)) baseCol.add(overrideObj);
                            }
                        } else {
                            baseMap.put(key, value);
                        }
                    } else {
                        // NOTE: no way to check empty, if not null not empty so put it
                        baseMap.put(key, value);
                    }
                }
            } else {
                baseMap.put(key, value);
            }
        }
    }

    public final static Collection<Object> singleNullCollection;

    static {
        singleNullCollection = new ArrayList<>();
        singleNullCollection.add(null);
    }

    /**
     * 从Map中删除具有空值的条目，为方便起见返回传入的Map（在删除之前不进行克隆！）。
     */
    @SuppressWarnings("unchecked")
    public static Map removeNullsFromMap(Map theMap) {
        if (theMap == null) return null;
        theMap.values().removeAll(singleNullCollection);
        return theMap;
    }

    public static boolean mapMatchesFields(Map<String, Object> baseMap, Map<String, Object> compareMap) {
        for (Map.Entry<String, Object> entry : compareMap.entrySet()) {
            Object compareObj = compareMap.get(entry.getKey());
            Object baseObj = baseMap.get(entry.getKey());
            if (compareObj == null) {
                if (baseObj != null) return false;
            } else {
                if (!compareObj.equals(baseObj)) return false;
            }
        }
        return true;
    }

    public static Node deepCopyNode(Node original) {
        return deepCopyNode(original, null);
    }

    @SuppressWarnings("unchecked")
    public static Node deepCopyNode(Node original, Node parent) {
        if (original == null) return null;

        Node newNode = new Node(parent, original.name(), new HashMap(original.attributes()));
        Object newValue = original.value();
        if (newValue instanceof List) {
            NodeList childList = new NodeList();
            for (Object child : (List) newValue) {
                if (child instanceof Node) {
                    childList.add(deepCopyNode((Node) child, newNode));
                } else if (child != null) {
                    childList.add(child);
                }
            }
            newValue = childList;
        }

        if (newValue != null) newNode.setValue(newValue);
        return newNode;
    }

    public static String nodeText(Object nodeObj) {
        if (!DefaultGroovyMethods.asBoolean(nodeObj)) return "";
        Node theNode = null;
        if (nodeObj instanceof Node) {
            theNode = (Node) nodeObj;
        } else if (nodeObj instanceof NodeList) {
            NodeList nl = DefaultGroovyMethods.asType((Collection) nodeObj, NodeList.class);
            if (nl.size() > 0) theNode = (Node) nl.get(0);
        }

        if (theNode == null) return "";
        List<String> textList = theNode.localText();
        if (DefaultGroovyMethods.asBoolean(textList)) {
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

    public static Node nodeChild(Node parent, String childName) {
        if (parent == null) return null;
        NodeList childList = (NodeList) parent.get(childName);
        if (childList != null && childList.size() > 0) return (Node) childList.get(0);
        return null;
    }

    public static void paginateList(String listName, String pageListName, Map<String, Object> context) {
        if (pageListName == null || pageListName.isEmpty()) pageListName = listName;
        List theList = (List) context.get(listName);
        if (theList == null) theList = new ArrayList();

        final Object pageIndexObj = context.get("pageIndex");
        int pageIndex = ObjectUtil.isEmpty(pageIndexObj) ? 0 : Integer.parseInt(pageIndexObj.toString());
        final Object pageSizeObj = context.get("pageSize");
        int pageSize = ObjectUtil.isEmpty(pageSizeObj) ? 20 : Integer.parseInt(pageSizeObj.toString());

        int count = theList.size();

        // calculate the pagination values
        int maxIndex = (new BigDecimal(count - 1)).divide(new BigDecimal(pageSize), 0, BigDecimal.ROUND_DOWN).intValue();
        int pageRangeLow = (pageIndex * pageSize) + 1;
        if (pageRangeLow > count) pageRangeLow = count + 1;
        int pageRangeHigh = (pageIndex * pageSize) + pageSize;
        if (pageRangeHigh > count) pageRangeHigh = count;

        List pageList = theList.subList(pageRangeLow - 1, pageRangeHigh);
        context.put(pageListName, pageList);
        context.put(pageListName + "Count", count);
        context.put(pageListName + "PageIndex", pageIndex);
        context.put(pageListName + "PageSize", pageSize);
        context.put(pageListName + "PageMaxIndex", maxIndex);
        context.put(pageListName + "PageRangeLow", pageRangeLow);
        context.put(pageListName + "PageRangeHigh", pageRangeHigh);
    }
}
