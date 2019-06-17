/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 * 
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 * 
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package com.zmtech.zkit.entity;

import groovy.lang.Closure;

import java.io.Externalizable;
import java.io.Writer;
import java.sql.Timestamp;
import java.util.*;

/**
 * 包含实体对象的列表。
 * 实体列表含有一些额外的功能，如过滤到基本List<EntityValue>;
 * 这里的各种方法修改内部列表以提高效率，并为方便起见返回对此的引用。
 * 如果您想要一个带有修改的新EntityList，请使用clone（）或cloneList（）然后修改它。
 */
@SuppressWarnings("unused")
public interface EntityList extends List<EntityValue>, Iterable<EntityValue>, Cloneable, RandomAccess, Externalizable {

    /**
     * 取列表中的第一个值。
     * @return 列表中的第一个值.
     */
    EntityValue getFirst();

    /**
     * 列表过滤 按照日期区间过滤实体。
     * 结果包括与fromDate到thruDate区间的值。
     *@param fromDateName 起始时间字段的名称。 默认为“fromDate”。
     *@param thruDateName 截止时间字段的名称。 默认为“thruDate”。
     *@param moment 比较值的时间点; 如果为null，则使用当前系统日期/时间。
     *@return 当前对象
     */
    EntityList filterByDate(String fromDateName, String thruDateName, Timestamp moment);

    /**
     * 列表过滤 按照日期区间过滤实体。
     * 结果包括与fromDate到thruDate区间的值。
     *@param fromDateName 起始时间字段的名称。 默认为“fromDate”。
     *@param thruDateName 截止时间字段的名称。 默认为“thruDate”。
     *@param moment 比较值的时间点; 如果为null，则使用当前系统日期/时间。
     *@param ignoreIfEmpty true：忽略空值，false：不忽略空值。
     *@return 当前对象
     */
    EntityList filterByDate(String fromDateName, String thruDateName, Timestamp moment, boolean ignoreIfEmpty);

    /**
     * 列表过滤，使其仅包含与fields参数中的值匹配的值。
     *@param fields 要包含在输出列表中的值必须匹配的字段名称/字段值 map。
     *@return 当前对象
     */
    EntityList filterByAnd(Map<String, Object> fields);

    /**
     * 列表过滤，使其仅包含与fields参数中的值匹配的值。
     *@param fields 要包含在输出列表中的值必须匹配的字段名称/字段值 map。
     *@param include true：
     *@return 当前对象
     */
    EntityList filterByAnd(Map<String, Object> fields, Boolean include);

    /** Modify this EntityList so that it contains only the values that match the values in the namesAndValues parameter.
     *
     *@param namesAndValues Must be an even number of parameters as field name then value repeated as needed
     *@return List of EntityValue objects that match the values in the fields parameter.
     */
    EntityList filterByAnd(Object... namesAndValues);

    EntityList removeByAnd(Map<String, Object> fields);

    /** Modify this EntityList so that it includes (or excludes) values matching the condition.
     *
     * @param condition EntityCondition to filter by.
     * @param include If true include matching values, if false exclude matching values.
     *     Defaults to true (include, ie only include values that do meet condition).
     * @return List with filtered values.
     */
    EntityList filterByCondition(EntityCondition condition, Boolean include);

    /**
     * 修改此EntityList，使其包含（或排除）闭包计算结果为true的实体值。
     * The closure is called with a single argument, the current EntityValue in the list, and should evaluate to a Boolean. */
    EntityList filter(Closure<Boolean> closure, Boolean include);

    /** Find the first value in this EntityList where the closure evaluates to true. */
    EntityValue find(Closure<Boolean> closure);
    /** Different from filter* method semantics, does not modify this EntityList. Returns a new EntityList with just the
     * values where the closure evaluates to true. */
    EntityList findAll(Closure<Boolean> closure);

    /** Modify this EntityList to only contain up to limit values starting at the offset.
     *
     * @param offset Starting index to include
     * @param limit Include only this many values
     * @return List with filtered values.
     */
    EntityList filterByLimit(Integer offset, Integer limit);
    /** For limit filter in a cached entity-find with search-form-inputs, done after the query */
    EntityList filterByLimit(String inputFieldsMapName, boolean alwaysPaginate);

    /** The offset used to filter the list, if filterByLimit has been called. */
    Integer getOffset();
    /** The limit used to filter the list, if filterByLimit has been called. */
    Integer getLimit();
    /** For use with filterByLimit when paginated. Equals offset (default 0) divided by page size. */
    int getPageIndex();
    /** For use with filterByLimit when paginated. Equals limit (default 20; for use along with getPageIndex()). */
    int getPageSize();

    /** Modify this EntityList so that is ordered by the field names passed in.
     *
     *@param fieldNames The field names for the entity values to sort the list by. Optionally prefix each field name
     * with a plus sign (+) for ascending or a minus sign (-) for descending. Defaults to ascending.
     *@return List of EntityValue objects in the specified order.
     */
    EntityList orderByFields(List<String> fieldNames);

    int indexMatching(Map<String, Object> valueMap);
    void move(int fromIndex, int toIndex);

    /** Adds the value to this list if the value isn't already in it. Returns reference to this list. */
    EntityList addIfMissing(EntityValue value);
    /** Adds each value in the passed list to this list if the value isn't already in it. Returns reference to this list. */
    EntityList addAllIfMissing(EntityList el);

    /** Writes XML text with an attribute or CDATA element for each field of each record. If dependents is true also
     * writes all dependent (descendant) records.
     * @param writer A Writer object to write to
     * @param prefix A prefix to put in front of the entity name in the tag name
     * @param dependentLevels Write dependent (descendant) records this many levels deep, zero for no dependents
     * @return The number of records written
     */
    int writeXmlText(Writer writer, String prefix, int dependentLevels);

    /** Method to implement the Iterable interface to allow an EntityList to be used in a foreach loop.
     *
     * @return Iterator&lt;EntityValue&gt; to iterate over internal list.
     */
    @Override
    Iterator<EntityValue> iterator();

    /** Get a list of Map (not EntityValue) objects. If dependentLevels is greater than zero includes nested dependents
     * in the Map for each value. */
    List<Map<String, Object>> getPlainValueList(int dependentLevels);
    List<Map<String, Object>> getMasterValueList(String name);
    ArrayList<Map<String, Object>> getValueMapList();

    EntityList cloneList();

    void setFromCache();
    boolean isFromCache();
}
