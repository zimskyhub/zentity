package com.zmtech.zkit.entity;

import java.io.Externalizable;
import java.util.Map;

@SuppressWarnings("unused")
public interface EntityCondition extends Externalizable {

    // 相等
    ComparisonOperator EQUALS = ComparisonOperator.EQUALS;
    // 不等
    ComparisonOperator NOT_EQUAL = ComparisonOperator.NOT_EQUAL;
    // 小于
    ComparisonOperator LESS_THAN = ComparisonOperator.LESS_THAN;
    // 大于
    ComparisonOperator GREATER_THAN = ComparisonOperator.GREATER_THAN;
    // 小于等于
    ComparisonOperator LESS_THAN_EQUAL_TO = ComparisonOperator.LESS_THAN_EQUAL_TO;
    // 大于等于
    ComparisonOperator GREATER_THAN_EQUAL_TO = ComparisonOperator.GREATER_THAN_EQUAL_TO;
    // 包含
    ComparisonOperator IN = ComparisonOperator.IN;
    // 不包含
    ComparisonOperator NOT_IN = ComparisonOperator.NOT_IN;
    // 之内
    ComparisonOperator BETWEEN = ComparisonOperator.BETWEEN;
    // 之外
    ComparisonOperator NOT_BETWEEN = ComparisonOperator.NOT_BETWEEN;
    // 相同
    ComparisonOperator LIKE = ComparisonOperator.LIKE;
    // 不相同
    ComparisonOperator NOT_LIKE = ComparisonOperator.NOT_LIKE;
    // 为空
    ComparisonOperator IS_NULL = ComparisonOperator.IS_NULL;
    // 不为空
    ComparisonOperator IS_NOT_NULL = ComparisonOperator.IS_NOT_NULL;

    // 与
    JoinOperator AND = JoinOperator.AND;
    // 或
    JoinOperator OR = JoinOperator.OR;

    // 条件操作符
    enum ComparisonOperator { EQUALS, NOT_EQUAL,
        LESS_THAN, GREATER_THAN, LESS_THAN_EQUAL_TO, GREATER_THAN_EQUAL_TO,
        IN, NOT_IN, BETWEEN, NOT_BETWEEN, LIKE, NOT_LIKE, IS_NULL, IS_NOT_NULL }
    // 条件操作符
    enum JoinOperator { AND, OR }

    /** 匹配内存中的条件 */
    boolean mapMatches(Map<String, Object> map);
    /** 在ViewEntity的成员发生变化时用于清空实体缓存 */
    boolean mapMatchesAny(Map<String, Object> map);
    /** 在ViewEntity的成员发生变化时用于清空实体缓存 */
    boolean mapKeysNotContained(Map<String, Object> map);
    /** 创建一个 名称/值 的Map 来代表条件，当条件创建失败时返回false */
    boolean populateMap(Map<String, Object> map);

    /**
     * 设置忽略的查询条件
     * 作用于所有条件类型
     * @return 当前条件对象.
     */
    EntityCondition ignoreCase();
}

