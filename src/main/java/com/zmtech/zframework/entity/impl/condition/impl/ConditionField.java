package com.zmtech.zframework.entity.impl.condition.impl;

import com.zmtech.zframework.exception.EntityException;
import com.zmtech.zframework.entity.impl.EntityDefinition;
import com.zmtech.zframework.entity.impl.FieldInfo;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;

public class ConditionField implements Externalizable {
    private static final Class thisClass = ConditionField.class;
    String fieldName;
    private int curHashCode;
    private FieldInfo fieldInfo = null;

    public ConditionField() {
    }

    public ConditionField(String fieldName) {
        if (fieldName == null) throw new EntityException("字段名称不能为空!");
        this.fieldName = fieldName.intern();
        curHashCode = this.fieldName.hashCode();
    }

    public ConditionField(FieldInfo fi) {
        if (fi == null) throw new EntityException("字段信息不能为空!");
        fieldInfo = fi;
        // fi.name is interned in makeFieldInfo()
        fieldName = fi.name;
        curHashCode = fieldName.hashCode();
    }

    public String getFieldName() {
        return fieldName;
    }

    public String getColumnName(EntityDefinition ed) {
        if (fieldInfo != null && fieldInfo.ed.fullEntityName.equals(ed.fullEntityName))
            return fieldInfo.getFullColumnName();
        return ed.getColumnName(fieldName);
    }

    public FieldInfo getFieldInfo(EntityDefinition ed) {
        if (fieldInfo != null && fieldInfo.ed.fullEntityName.equals(ed.fullEntityName)) return fieldInfo;
        return ed.getFieldInfo(fieldName);
    }

    @Override
    public String toString() {
        return fieldName;
    }

    @Override
    public int hashCode() {
        return curHashCode;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null) return false;
        // 因为从EntityDefinition重用，这可能是同一个对象，所以先检查一下
        if (this == o) return true;
        if (o.getClass() != thisClass) return false;
        ConditionField that = (ConditionField) o;
        // intern'ed String to use == operator
        return fieldName.equals(that.fieldName);
    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
        out.writeObject(fieldName.toCharArray());
    }

    @Override
    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        fieldName = new String((char[]) in.readObject()).intern();
        curHashCode = fieldName.hashCode();
    }
}
