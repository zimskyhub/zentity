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
package com.zmtech.zentity.entity;

import com.zmtech.zentity.etl.SimpleEtl;
import com.zmtech.zentity.exception.EntityException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 用于为实体查找（查询）设置各种选项
 * 设置选项的所有方法都会修改该选项，然后返回此修改后的对象以允许方法调用链接。
 * 重要的是要注意，此对象不是不可变的并且在内部进行了修改，为了方便起见，返回EntityFind只是一个自引用。
 * 即使在查询之后，也可以修改查找对象，然后使用它来执行另一个查询。
 */
@SuppressWarnings("unused")
public interface EntityFind extends java.io.Serializable, SimpleEtl.Extractor {

    /**
     * 实体名称
     * 要使用的实体的名称，如实体XML文件中所定义.
     * @return 当前对象.
     */
    EntityFind entity(String entityName);

    /**
     * 取实体名称
     * @return 实体名称.
     */
    String getEntity();

    /**
     * 转换为动态视图
     * 使用动态视图对象而不是实体名称（如果使用，将忽略实体名称）
     * 如果多次调用将返回相同的对象.
     *
     * @return EntityDynamicView.
     */
    EntityDynamicView makeEntityDynamicView();

    // ======================== Conditions (Where and Having) =================

    /**
     * 添加条件
     * 在find（where子句）中添加一个字段
     * 如果已使用相同名称设置字段，则将替换该字段的值。
     * 如果已经存在任何其他约束，则将对它们进行AND运算。
     * @param fieldName 字段名称
     * @param value 字段值
     * @return 当前对象.
     */
    EntityFind condition(String fieldName, Object value);

    /**
     * 添加条件
     * 在find（where子句）中添加一个条件
     * 使用运算符将命名字段与值进行比较。
     * @param fieldName 字段名称
     * @param operator 操作符
     * @param value 字段值
     * @return 当前对象.
     */
    EntityFind condition(String fieldName, EntityCondition.ComparisonOperator operator, Object value);

    /**
     * 添加条件
     * 在find（where子句）中添加一个条件
     * 使用运算符将命名字段与值进行比较。
     * @param fieldName 字段名称
     * @param operator 操作符
     * @param value 字段值
     * @return 当前对象.
     */
    EntityFind condition(String fieldName, String operator, Object value);

    /**
     * 添加条件
     * 使用运算符将字段与另一个字段进行比较。
     * @param fieldName 字段名称
     * @param operator 操作符
     * @param toFieldName 字段名称
     * @return 当前对象.
     */
    EntityFind conditionToField(String fieldName, EntityCondition.ComparisonOperator operator, String toFieldName);

    /**
     * 添加条件
     * 将Map映射到字段添加到find（where子句）。
     * 如果已使用相同名称和任何Map键设置字段，则将替换该字段的值。
     * 以这种方式设置的字段将在执行查询之前与其他条件（如果适用）组合。
     * 如果需要，这将根据需要从字符串到字段类型进行转换，并且只获取与实体字段匹配的键。
     * 它的作用与：<code> EntityValue.setFields（fields，true，null，null）</ code> 相同
     * @param fields 字段映射Map
     * @return 当前对象.
     */
    EntityFind condition(Map<String, Object> fields);

    /**
     * 添加条件
     * 将一个EntityCondition添加到find（where子句）。
     * @param condition 条件
     * @return 当前对象.
     */
    EntityFind condition(EntityCondition condition);

    /**
     * 添加条件
     * 为标准生效日期查询模式添加条件，包括from字段为null或早于或等于compareStamp，并且thru字段为null或晚于或等于compareStamp。
     * @param fromFieldName 开始字段
     * @param thruFieldName 截止字段
     * @param compareStamp 时间戳
     * @return 当前对象.
     */
    EntityFind conditionDate(String fromFieldName, String thruFieldName, java.sql.Timestamp compareStamp);

    /**
     * 是否含条件
     * @return 含有条件：true 不含条件：false
     */
    boolean getHasCondition();

    /**
     * 是否含条件
     * @return 含有条件：true 不含条件：false
     */
    boolean getHasHavingCondition();

    /**
     * 添加having条件
     * 如果已经存在约束，则将对它们进行AND运算。
     * @param condition 条件
     * @return 当前对象.
     */
    EntityFind havingCondition(EntityCondition condition);

    /**
     * 取 where 条件
     * @return 条件.
     */
    EntityCondition getWhereEntityCondition();

    /**
     * 取 having 条件
     * @return 条件.
     */
    EntityCondition getHavingEntityCondition();

    // ======================== General/Common Options ========================

    /**
     * 添加实体的字段。
     * 如果已经指定了任何选择字段，则会将其添加到集合中。
     * @param fieldToSelect 字段名称
     * @return 当前对象.
     */
    EntityFind selectField(String fieldToSelect);

    /**
     * 添加实体的字段。
     * 如果为空或null将检索所有字段。
     * @param fieldsToSelect 字段集合
     * @return 当前对象.
     */
    EntityFind selectFields(Collection<String> fieldsToSelect);

    /**
     * 取查询的字段集合
     * @return 字段集合.
     */
    List<String> getSelectFields();

    /**
     * 排序
     * 查找实体的字段，用于对查询进行排序。
     * 可选择将“ASC”添加到结尾，或者将“+”添加到开头以进行升序，或者将“DESC”添加到“ - ”的末尾，然后添加到降序的开头。
     * 可选择将“ASC”添加到结尾，或者将“+”添加到开头以进行升序，或者将“DESC”添加到“ - ”的末尾，然后添加到降序的开头。
     * 如果已经指定了任何其他排序，则会将其添加到列表的末尾。
     * String可以是以逗号分隔的字段名称列表。 只有实体上存在的字段才会按列表添加到排序中。
     * @return 当前对象.
     */
    EntityFind orderBy(String orderByFieldName);

    /**
     * 排序
     * 每个List条目都传递给orderBy（String orderByFieldName）方法，有关详细信息，请参阅它。
     * @return 当前对象.
     */
    EntityFind orderBy(List<String> orderByFieldNames);
    List<String> getOrderBy();

    /**
     * 是否开启缓存
     * 默认值在实体定义中
     * @param useCache 使用缓存:true 不不使用:false
     * @return 当前对象.
     */
    EntityFind useCache(Boolean useCache);
    boolean getUseCache();

    // ======================== Advanced Options ==============================

    /**
     * 去重
     * 指定是否应过滤返回的值以删除重复值。
     * 默认是 false.
     * @param distinct 去重:true 不去重:false
     * @return 当前对象.
     */
    EntityFind distinct(boolean distinct);
    boolean getDistinct();

    /**
     * 实体查询起始行
     * 默认（null）表示从第一行开始。
     * @param offset 起始行数
     * @return 当前对象.
     */
    EntityFind offset(Integer offset);

    /**
     * 实体查询起始行
     * @param pageIndex 页面索引
     * @param pageSize 单页数量
     * @return 当前对象.
     */
    EntityFind offset(int pageIndex, int pageSize);
    Integer getOffset();

    /**
     * 返回的最大行数。
     * 默认值（null）表示所有行。
     * 仅适用于list（）和iterator（）查找。
     * @return 当前对象.
     */
    EntityFind limit(Integer limit);

    /**
     * 取当前查询的最大行数。
     * @return 当前查询的最大行数.
     */
    Integer getLimit();

    /**
     * 取页面索引
     * 用于分页。 等于偏移（默认为0）除以页面大小。
     * */
    int getPageIndex();

    /**
     * 取单页数量
     * 等于限制（默认为20;与getPageIndex（）一起存在以保持一致性）。
     */
    int getPageSize();

    /**
     * 锁定所选实体，以便只有此事务可以更改它直到结束。
     * 如果在完成查找时设置了此项，则将忽略useCache设置，因为这将始终从数据库获取数据。
     * @param forUpdate 锁定:true 不锁定:false
     * @return 当前对象.
     */
    EntityFind forUpdate(boolean forUpdate);

    /**
     * 取锁定状态
     * @return true:锁定 false:不锁定
     */
    boolean getForUpdate();

    // ======================== JDBC 设置 ==============================

    /**
     * 指定如何遍历ResultSet。
     * 可用：ResultSet.TYPE_FORWARD_ONLY，ResultSet.TYPE_SCROLL_INSENSITIVE (默认值) ResultSet.TYPE_SCROLL_SENSITIVE
     * 有关更多信息，请参阅java.sql.ResultSet JavaDoc。
     * 如果您希望它快，请使用公共选项：ResultSet.TYPE_FORWARD_ONLY。
     * 对于要跳转到索引的部分结果，请确保使用TYPE_SCROLL_INSENSITIVE。
     * 默认为ResultSet.TYPE_SCROLL_INSENSITIVE。
     * @return 当前对象
     */
    EntityFind resultSetType(int resultSetType);

    /**
     * 取遍历ResultSet类型
     * @return 遍历ResultSet类型
     */
    int getResultSetType();

    /**
     * 指定是否可以更新ResultSet。 可用值：
     * ResultSet.CONCUR_READ_ONLY（默认值）或 ResultSet.CONCUR_UPDATABLE。
     * 应该总是ResultSet.CONCUR_READ_ONLY，因为更新通常作为单独的操作完成。
     * 默认为CONCUR_READ_ONLY。
     * @return 当前对象
     */
    EntityFind resultSetConcurrency(int resultSetConcurrency);
    int getResultSetConcurrency();

    /**
     * 此查询的JDBC 查询包容量。
     * 默认（null）将回退到数据源设置。
     * 不是OFFSET / FETCH SQL子句中的提取（使用限制），而是用于确定JDBC到database提取每次往返返回的行数的容量 。
     * 仅仅适用于list（）和iterator（）查找。
     * @param fetchSize 包容量
     * @return 当前对象。
     */
    EntityFind fetchSize(Integer fetchSize);

    /**
     * 取当前包容量
     * @return 包容量
     */
    Integer getFetchSize();

    /**
     * 查询的JDBC最大行数。
     * 默认（null）将使用数据源的设置。
     * 这是ResultSet在释放它们之前在任何给定时间保留在内存中的最大行数，如果请求，它们将再次从数据库中检索。
     * 仅适用于list（）和iterator（）查找。
     * @return 当前对象。
     */
    EntityFind maxRows(Integer maxRows);

    /**
     * 取当前最大行数
     * @return 最大行数
     */
    Integer getMaxRows();

    /**
     * 关闭数据权限
     * @return 当前对象
     */
    EntityFind disableAuthz();

    /**
     * 查询是否使用了缓存
     * @return true:使用缓存 false:不适用缓存
     */
    boolean shouldCache();

    // ======================== 查询方法 ==============================

    /**
     * 按主键查找单个实体
     * @return 实体
     * @throws EntityException 实体操作错误
     */
    EntityValue one() throws EntityException;

    /**
     * 按主键查找单个实体，然后根据指定的主定义获取所有相关/依赖实体
     * （默认名称为“default”）。
     * @param name master名称
     * @return 依赖实体
     * @throws EntityException 实体操作错误
     */
    Map<String, Object> oneMaster(String name) throws EntityException;

    /**
     * 查找实体列表
     * @return 实体列表
     * @throws EntityException 实体操作错误
     */
    EntityList list() throws EntityException;

    /** 查找实体列表，然后为每个结果根据指定的主定义获取所有相关/依赖实体列表
     * （默认名称为'default'）
     * @param name master名称
     * @return 依赖实体列表
     * @throws EntityException 实体操作错误
     */
    List<Map<String, Object>> listMaster(String name) throws EntityException;

    /**
     * 查找并返回EntityListIterator对象。
     * 此方法忽略缓存设置，并始终从数据库中获取结果。
     * @return EntityListIterator对象。
     *
     */
    EntityListIterator iterator() throws EntityException;

    /**
     * 查找实体的总数。
     * @return 实体总数
     */
    long count() throws EntityException;

    /**
     * 使用Map映射更新实体。
     * @param fieldsToSet 实体映射
     * @return 受此操作影响的行数
     * @throws EntityException 实体操作错误
     */
    long updateAll(Map<String, ?> fieldsToSet) throws EntityException;

    /**
     * 删除与条件匹配的实体。
     * @return 受此操作影响的行数
     * @throws EntityException 实体操作错误
     */
    long deleteAll() throws EntityException;

    /**
     * 如果基础数据源支持，则获取用于查找查询的文本（SQL等）。
     * 如果使用此查找完成多个查询，则将具有多个值。
     * @return 文本列表
     */
    ArrayList<String> getQueryTextList();
}
