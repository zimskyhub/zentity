package com.zmtech.zkit.entity.impl.condition;

import com.zmtech.zkit.entity.EntityCondition;
import com.zmtech.zkit.entity.impl.EntityDefinition;
import com.zmtech.zkit.entity.impl.EntityQueryBuilder;

import java.util.Set;

public interface EntityConditionImplBase extends EntityCondition {

    /**
     * 为据库中条件构建SQL WHERE子句。
     */
    void makeSqlWhere(EntityQueryBuilder eqb, EntityDefinition subMemberEd);

    void getAllAliases(Set<String> entityAliasSet, Set<String> fieldAliasSet);

    /**
     * 仅获取视图实体的成员实体中的字段的条件，如果为null则 sub-select = true的成员实体没有别名
     */
    EntityConditionImplBase filter(String entityAlias, EntityDefinition mainEd);
}
