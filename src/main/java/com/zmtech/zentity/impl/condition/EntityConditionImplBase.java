package com.zmtech.zentity.impl.condition;

import com.zmtech.zentity.EntityCondition;
import com.zmtech.zentity.impl.EntityDefinition;
import com.zmtech.zentity.impl.EntityQueryBuilder;

import java.util.Set;

public interface EntityConditionImplBase extends EntityCondition {

    /** Build SQL WHERE clause text to evaluate condition in a database. */
    void makeSqlWhere(EntityQueryBuilder eqb, EntityDefinition subMemberEd);

    void getAllAliases(Set<String> entityAliasSet, Set<String> fieldAliasSet);
    /** Get only conditions for fields in the member-entity of a view-entity, or if null then all aliases for member entities without sub-select=true */
    EntityConditionImplBase filter(String entityAlias, EntityDefinition mainEd);
}
