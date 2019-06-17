package com.zmtech.zkit.entity.impl.condition.impl;

import com.zmtech.zkit.entity.impl.FieldInfo;
import com.zmtech.zkit.exception.EntityException;
import com.zmtech.zkit.entity.impl.EntityDefinition;
import com.zmtech.zkit.util.MNode;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;

public class ConditionAlias extends ConditionField implements Externalizable {
    private static final Class thisClass = ConditionAlias.class;

    private String fieldName;
    private String entityAlias = null;
    private String aliasEntityName = null;
    private transient EntityDefinition aliasEntityDefTransient = null;
    private int curHashCode;

    public ConditionAlias() {
    }

    public ConditionAlias(String entityAlias, String fieldName, EntityDefinition aliasEntityDef) {
        if (fieldName == null) throw new EntityException("字段名称不能为空!");
        if (entityAlias == null) throw new EntityException("字段别名不能为空!");
        if (aliasEntityDef == null) throw new EntityException("实体别名不能为空!");
        this.fieldName = fieldName.intern();
        this.entityAlias = entityAlias.intern();

        aliasEntityDefTransient = aliasEntityDef;
        String entName = aliasEntityDef.getFullEntityName();
        aliasEntityName = entName.intern();
        curHashCode = createHashCode();
    }

    public String getEntityAlias() {
        return entityAlias;
    }

    public String getFieldName() {
        return fieldName;
    }

    public String getAliasEntityName() {
        return aliasEntityName;
    }

    private EntityDefinition getAliasEntityDef(EntityDefinition otherEd) {
        if (aliasEntityDefTransient == null && aliasEntityName != null)
            aliasEntityDefTransient = otherEd.getEfi().getEntityDefinition(aliasEntityName);
        return aliasEntityDefTransient;
    }

    public String getColumnName(EntityDefinition ed) {
        StringBuilder colName = new StringBuilder();
        // 注意：这可能会导致视图实体成为具有函数或其他的成员实体; 我们可能必须传递前缀才能在函数或其他中添加它
        colName.append(entityAlias).append('.');
        EntityDefinition memberEd = getAliasEntityDef(ed);
        if (memberEd.isViewEntity) {
            MNode memberEntity = ed.getMemberEntityNode(entityAlias);
            if ("true".equals(memberEntity.attribute("sub-select")))
                colName.append(memberEd.getFieldInfo(fieldName).columnName);
            else colName.append(memberEd.getColumnName(fieldName));
        } else {
            colName.append(memberEd.getColumnName(fieldName));
        }
        return colName.toString();
    }

    public FieldInfo getFieldInfo(EntityDefinition ed) {
        if (aliasEntityName != null) {
            return getAliasEntityDef(ed).getFieldInfo(fieldName);
        } else {
            return ed.getFieldInfo(fieldName);
        }
    }

    @Override
    public String toString() {
        return (entityAlias != null ? (entityAlias + ".") : "") + fieldName;
    }

    @Override
    public int hashCode() {
        return curHashCode;
    }

    private int createHashCode() {
        return fieldName.hashCode() + (entityAlias != null ? entityAlias.hashCode() : 0) +
                (aliasEntityName != null ? aliasEntityName.hashCode() : 0);
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || o.getClass() != thisClass) return false;
        ConditionAlias that = (ConditionAlias) o;
        // both Strings are intern'ed so use != operator for object compare
        if (fieldName != that.fieldName) return false;
        if (entityAlias != that.entityAlias) return false;
        if (aliasEntityName != that.aliasEntityName) return false;
        return true;
    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
        out.writeUTF(fieldName);
        out.writeUTF(entityAlias);
        out.writeUTF(aliasEntityName);
    }

    @Override
    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        fieldName = in.readUTF().intern();
        entityAlias = in.readUTF().intern();
        aliasEntityName = in.readUTF().intern();
        curHashCode = createHashCode();
    }
}
