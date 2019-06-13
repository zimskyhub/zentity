package com.zmtech.zentity;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 *
 * 条件生产者
 */
@SuppressWarnings("unused")
public interface EntityConditionFactory {

    EntityCondition getTrueCondition();

    /**
     * 创建条件 :联合条件
     * @param lhs       左条件
     * @param operator  操作 OR/AND
     * @param rhs       右条件
     * @return          条件
     */
    EntityCondition makeCondition(EntityCondition lhs, EntityCondition.JoinOperator operator, EntityCondition rhs);

    /**
     * 创建条件
     * @param field     字段名称
     * @param operator  操作符
     * @param value     值
     * @return          条件
     */
    EntityCondition makeCondition(String field, EntityCondition.ComparisonOperator operator, Object value);

    /**
     * 创建条件
     * @param field     字段名称
     * @param operator  操作符
     * @param toField   对比字段名称
     * @return          条件
     */
    EntityCondition makeConditionToField(String field, EntityCondition.ComparisonOperator operator, String toField);

    /**
     * 创建条件
     * NOTE : 默认操作符为 AND
     * @param conditionList 条件列表
     * @return              条件
     */
    EntityCondition makeCondition(List<EntityCondition> conditionList);

    /**
     * 创建条件
     * @param conditionList 条件列表
     * @param operator      操作符
     * @return              条件
     */
    EntityCondition makeCondition(List<EntityCondition> conditionList, EntityCondition.JoinOperator operator);


    /** More convenient for scripts, etc. The conditionList parameter may contain Map or EntityCondition objects. */
    /**
     * 创建条件
     * @param conditionList         条件列表
     * @param listOperator          操作符：OR、AND
     * @param mapComparisonOperator TODO 转义
     * @param mapJoinOperator
     * @return
     */
    EntityCondition makeCondition(List<Object> conditionList, String listOperator, String mapComparisonOperator, String mapJoinOperator);

    /**
     * 创建条件
     * @param fieldMap              字段集合
     * @param comparisonOperator    条件操作符
     * @param joinOperator          集合操作符
     * @return                      条件
     */
    EntityCondition makeCondition(Map<String, Object> fieldMap, EntityCondition.ComparisonOperator comparisonOperator, EntityCondition.JoinOperator joinOperator);

    /**
     * 创建条件
     * NOTE:默认的条件操作符为 EQUALS 集合操作符为 AND
     * @param fieldMap  字段集合
     * @return          条件
     */
    EntityCondition makeCondition(Map<String, Object> fieldMap);

    /**
     * 创建条件
     * 用于时间条件查询
     * @param fromField     开始字段
     * @param thruField     截止字段
     * @param compareStamp  时间
     * @return              条件
     */
    EntityCondition makeConditionDate(String fromField, String thruField, Timestamp compareStamp);

    /**
     * 创建条件
     * 自定义sql条件
     * @param sqlWhereClause    sql条件
     * @return                  条件
     */
    EntityCondition makeConditionWhere(String sqlWhereClause);

    /**
     * 取条件操作符
     * @param enumId    枚举代码
     * @return          条件操作符
     */
    EntityCondition.ComparisonOperator comparisonOperatorFromEnumId(String enumId);
}
