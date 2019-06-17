package com.zmtech.zkit.entity.impl.condition.impl;

import com.zmtech.zkit.entity.EntityCondition;
import com.zmtech.zkit.entity.impl.EntityDefinition;
import com.zmtech.zkit.entity.impl.EntityQueryBuilder;
import com.zmtech.zkit.entity.impl.condition.EntityConditionImplBase;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.Map;
import java.util.Set;

public class TrueCondition implements EntityConditionImplBase {
    private static final Class thisClass = TrueCondition.class;

    public TrueCondition() {
    }

    @Override
    public void makeSqlWhere(EntityQueryBuilder eqb, EntityDefinition subMemberEd) {
        eqb.sqlTopLevel.append("1=1");
    }

    @Override
    public boolean mapMatches(Map<String, Object> map) {
        return true;
    }

    @Override
    public boolean mapMatchesAny(Map<String, Object> map) {
        return true;
    }

    @Override
    public boolean mapKeysNotContained(Map<String, Object> map) {
        return true;
    }

    @Override
    public boolean populateMap(Map<String, Object> map) {
        return true;
    }

    @Override
    public void getAllAliases(Set<String> entityAliasSet, Set<String> fieldAliasSet) {
    }

    @Override
    public EntityConditionImplBase filter(String entityAlias, EntityDefinition mainEd) {
        return entityAlias == null ? this : null;
    }

    @Override
    public EntityCondition ignoreCase() {
        return this;
    }

    @Override
    public String toString() {
        return "1=1";
    }

    @Override
    public int hashCode() {
        return 127;
    }

    @Override
    public boolean equals(Object o) {
        return !(o == null || o.getClass() != thisClass);
    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
    }

    @Override
    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
    }
}
