package com.zmtech.zkit.entity.impl.condition.impl;

import com.zmtech.zkit.entity.EntityCondition;
import com.zmtech.zkit.entity.impl.EntityDefinition;
import com.zmtech.zkit.entity.impl.EntityQueryBuilder;
import com.zmtech.zkit.entity.impl.condition.EntityConditionImplBase;
import com.zmtech.zkit.exception.EntityException;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

public class DateCondition implements EntityConditionImplBase, Externalizable {

    protected String fromFieldName;
    protected String thruFieldName;
    protected Timestamp compareStamp;
    private EntityConditionImplBase conditionInternal;
    private int hashCodeInternal;

    public DateCondition(String fromFieldName, String thruFieldName, Timestamp compareStamp) {
        this.fromFieldName = fromFieldName != null ? fromFieldName : "fromDate";
        this.thruFieldName = thruFieldName != null ? thruFieldName : "thruDate";
        if (compareStamp == null) compareStamp = new Timestamp(System.currentTimeMillis());
        this.compareStamp = compareStamp;
        conditionInternal = makeConditionInternal();
        hashCodeInternal = createHashCode();
    }

    @Override
    public void makeSqlWhere(EntityQueryBuilder eqb, EntityDefinition subMemberEd) {
        conditionInternal.makeSqlWhere(eqb, subMemberEd);
    }

    @Override
    public void getAllAliases(Set<String> entityAliasSet, Set<String> fieldAliasSet) {
        fieldAliasSet.add(fromFieldName);
        fieldAliasSet.add(thruFieldName);
    }

    @Override
    public EntityConditionImplBase filter(String entityAlias, EntityDefinition mainEd) {
        return conditionInternal.filter(entityAlias, mainEd);
    }

    @Override
    public boolean mapMatches(Map<String, Object> map) {
        return conditionInternal.mapMatches(map);
    }

    @Override
    public boolean mapMatchesAny(Map<String, Object> map) {
        return conditionInternal.mapMatchesAny(map);
    }

    @Override
    public boolean mapKeysNotContained(Map<String, Object> map) {
        return conditionInternal.mapKeysNotContained(map);
    }

    @Override
    public boolean populateMap(Map<String, Object> map) {
        return false;
    }

    @Override
    public EntityCondition ignoreCase() {
        throw new EntityException("DateCondition 不支持 Ignore case!");
    }

    @Override
    public String toString() {
        return conditionInternal.toString();
    }

    private EntityConditionImplBase makeConditionInternal() {
        ConditionField fromFieldCf = new ConditionField(fromFieldName);
        ConditionField thruFieldCf = new ConditionField(thruFieldName);

        return new ListCondition(Arrays.asList(
                new ListCondition(Arrays.asList(
                        new FieldValueCondition(fromFieldCf, EQUALS, null),
                        new FieldValueCondition(fromFieldCf, LESS_THAN_EQUAL_TO, compareStamp)),
                        EntityCondition.JoinOperator.OR),
                new ListCondition(Arrays.asList(
                        new FieldValueCondition(thruFieldCf, EQUALS, null),
                        new FieldValueCondition(thruFieldCf, GREATER_THAN, compareStamp)),
                        EntityCondition.JoinOperator.OR)),
                EntityCondition.JoinOperator.AND);
    }

    @Override
    public int hashCode() {
        return hashCodeInternal;
    }

    private int createHashCode() {
        return compareStamp.hashCode() + fromFieldName.hashCode() + thruFieldName.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || o.getClass() != this.getClass()) return false;
        DateCondition that = (DateCondition) o;
        if (!this.compareStamp.equals(that.compareStamp)) return false;
        if (!fromFieldName.equals(that.fromFieldName)) return false;
        if (!thruFieldName.equals(that.thruFieldName)) return false;
        return true;
    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
        out.writeUTF(fromFieldName);
        out.writeUTF(thruFieldName);
        out.writeLong(compareStamp.getTime());
    }

    @Override
    public void readExternal(ObjectInput objectInput) throws IOException, ClassNotFoundException {
        fromFieldName = objectInput.readUTF();
        thruFieldName = objectInput.readUTF();
        compareStamp = new Timestamp(objectInput.readLong());
        hashCodeInternal = createHashCode();
        conditionInternal = makeConditionInternal();
    }
}
