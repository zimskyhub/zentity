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
package com.zmtech.zentity;

import com.zmtech.zentity.etl.SimpleEtl;
import com.zmtech.zentity.exception.EntityException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.sql.rowset.serial.SerialBlob;
import java.io.Externalizable;
import java.io.Writer;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;


/**
 * 实体对象
 * 用于承载数据和基本的数据操作
 */
@SuppressWarnings("unused")
public interface EntityValue extends Map<String, Object>, Externalizable, Comparable<EntityValue>, Cloneable, SimpleEtl.Entry {

    /**
     * 取实体定义名称
     * @return 实体名称
     */
    String getEntityName();

    /**
     * 取实体定义名称
     * @return 实体名称
     */
    String getEntityNamePretty();

    /**
     * 是否含有变更字段
     * @return 含有变更字段:true 没有:false
     */
    boolean isModified();

    /**
     * 字段是否变更
     * @param name 字段名称
     * @return 变更:true 没有变更:false
     */
    boolean isFieldModified(String name);

    /**
     * 字段是否赋值
     * @param name 字段名称
     * @return 赋值:true 未赋值:false
     */
    boolean isFieldSet(String name);

    /**
     * 字段是否属于该实体
     * 当字段不属于实体时 使用 get set 方法会抛出异常
     * @param name  字段名称
     * @return  属于:true 不属于:false
     */
    boolean isField(String name);

    /**
     * 实体是否可变
     * @return 可变:true 不可变:false
     */
    boolean isMutable();

    /**
     * 实体是否所有主键已赋值值
     * @return 已赋值:true 未赋值:false
     */
    boolean containsPrimaryKey();

    /**
     * 获取实体Map
     * 实体Map key代表字段名称 value代表字段值
     * @return 实体Map
     */
    Map<String, Object> getMap();

    /** 取字段值
     * 该方法支持通过实体关联名称取关联的实体
     * 格式为:"${title}${related-entity-name}"
     * 当取关联实体的时候相当于调用了
     * <code>findRelated(relationshipName, null, null, null, null)</code>:一对多
     * <code>findRelatedOne(relationshipName, null, null)</code> 一对一
     * @param name 字段名称或者关联的一对多 一对一名称
     * @return 字段值或者关联的实体
     */
    Object get(String name);

    /**
     * 取字段值
     * 不会检查字段是否存在
     * 不会取关联实体
     * 如果字段不存在 则返回null 并不会报错
     * @param name 字段名称
     * @return 字段值
     */
    Object getNoCheckSimple(String name);

    /**
     * 取实体主键Map
     * @return 实体主键Map
     */
    Map<String, Object> getPrimaryKeys();

    /**
     * 实体赋值
     * value 可以为 null
     * @param name 字段名称
     * @param value 字段值
     * @return 实体
     */
    EntityValue set(String name, Object value);

    /**
     * 实体赋值
     * 为多个字段赋值
     * Map 的 key 必须对应字段名称
     * @param fields 字段Map
     * @return 实体
     */
    EntityValue setAll(Map<String, Object> fields);

    /**
     * 实体赋值 字符串类型
     * @param name 字段名称
     * @param value 字段值
     * @return 实体
     */
    EntityValue setString(String name, String value);

    /**
     * 实体取值 String
     * @param name 字段名称
     * @return  字段值
     */
    String getString(String name);

    /**
     * 实体取值 Boolean
     * @param name 字段名称
     * @return 字段值
     */
    Boolean getBoolean(String name);

    /**
     * 实体取值 Timestamp
     * @param name 字段名称
     * @return 字段值
     */
    java.sql.Timestamp getTimestamp(String name);

    /**
     * 实体取值 Time
     * @param name 字段名称
     * @return 字段值
     */
    java.sql.Time getTime(String name);

    /**
     * 实体取值 Date
     * @param name 字段名称
     * @return 字段值
     */
    java.sql.Date getDate(String name);

    /**
     * 实体取值 Long
     * @param name 字段名称
     * @return 字段值
     */
    Long getLong(String name);

    /**
     * 实体取值 Double
     * @param name 字段名称
     * @return 字段值
     */
    Double getDouble(String name);

    /**
     * 实体取值 BigDecimal
     * @param name 字段名称
     * @return 字段值
     */
    BigDecimal getBigDecimal(String name);

    /**
     * 实体取值 byte[]
     * @param name 字段名称
     * @return 字段值
     */
    byte[] getBytes(String name);

    /**
     * 实体赋值 byte[]
     * @param name 字段名称
     * @return 字段值
     */
    EntityValue setBytes(String name, byte[] theBytes);

    /**
     * 实体取值 SerialBlob
     * @param name 字段名称
     * @return 字段值
     */
    SerialBlob getSerialBlob(String name);

    /**
     * 实体赋值 Map
     * 将Map对应的值付给实体 Map key 必须和实体字段对应
     * 当值类型为 String 会调用 setString方法
     * 其他会按照 Object类型 赋值
     * @param fields 字段Map
     * @param setIfEmpty 指定是否 空和null 的值 设不设置
     * @param namePrefix 字典前缀 设置会为所有字段添加前缀 原字段名称第一个字母会变成大写
     * @param pks null: 全部赋值 true: 只选择主键赋值 false: 只选择非主键赋值
     * @return 实体
     */
    EntityValue setFields(Map<String, Object> fields, boolean setIfEmpty, String namePrefix, Boolean pks);

    /**
     * 设置自增长主键
     * 取自增长的 sequencedId 并且把值赋予实体
     * 只能用于单主键实体
     * @return 实体
     */
    EntityValue setSequencedIdPrimary();

    /**
     * 设置自增长主键
     * 取自增长 sequencedId 并且取得存在的最大值，再加1 赋予实体主键
     * 只能用于单主键实体
     * @return 实体
     */
    EntityValue setSequencedIdSecondary();

    /**
     * 实体比较
     * 比较两个实体的值是否相等
     * @param that 对比实体
     * @return int -1 0 1
     */
    @Override
    int compareTo(EntityValue that);

    /**
     * 实体Map比较
     * @param theMap 实体map
     * @return 相等 true 不等 false
     */
    boolean mapMatches(Map<String, Object> theMap);

    EntityValue cloneValue();

    /**
     * 创建实体
     * @return 实体
     * @throws EntityException 创建错误
     */
    EntityValue create() throws EntityException;

    /**
     * 创建或更新实体
     * 当数据库记录存在时更新 不存在时新建
     * @return 实体
     * @throws EntityException 创建或更新错误
     */
    EntityValue createOrUpdate() throws EntityException;

    /** 等同于 createOrUpdate() */
    EntityValue store() throws EntityException;

    /**
     * 更新实体
     * 必须数据库存在实体主键的数据
     * @return 实体
     * @throws EntityException 更新错误
     */
    EntityValue update() throws EntityException;

    /**
     * 删除实体
     * 必须数据库存在实体主键的数据
     * 删除后返回的实体只代表数据
     * @return 实体
     * @throws EntityException 删除错误
     */
    EntityValue delete() throws EntityException;

    /**
     * 同步实体
     * 同步数据库数据到实体中
     * 实体主键必须在数据库中存在
     * @return 成功:true 刷新失败:false
     */
    boolean refresh() throws EntityException;

    Object getOriginalDbValue(String name);

    /** Get the named Related Entity for the EntityValue from the persistent store
     * @param relationshipName String containing the relationship name which is the combination of relationship.title
     *   and relationship.related-entity-name as specified in the entity XML definition file
     * @param byAndFields the fields that must equal in order to keep; may be null
     * @param orderBy The fields of the named entity to order the query by; may be null;
     *      optionally add a " ASC" for ascending or " DESC" for descending
     * @param useCache Look in the cache before finding in the datasource. Defaults to setting on entity definition.
     * @return List of EntityValue instances as specified in the relation definition
     */
    EntityList findRelated(String relationshipName, Map<String, Object> byAndFields, List<String> orderBy,
                           Boolean useCache, Boolean forUpdate) throws EntityException;

    /** Get the named Related Entity for the EntityValue from the persistent store
     * @param relationshipName String containing the relationship name which is the combination of relationship.title
     *   and relationship.related-entity-name as specified in the entity XML definition file
     * @param useCache Look in the cache before finding in the datasource. Defaults to setting on entity definition.
     * @return List of EntityValue instances as specified in the relation definition
     */
    EntityValue findRelatedOne(String relationshipName, Boolean useCache, Boolean forUpdate) throws EntityException;

    long findRelatedCount(final String relationshipName, Boolean useCache);

    /** Find all records with a foreign key reference to this record. Operates on relationship definitions for any related entity
     * that has a type one relationship to this entity.
     *
     * Does not recurse, finds directly related (dependant) records only.
     *
     * Will skip any related records whose entity name is in skipEntities.
     *
     * Useful as a validation before calling deleteWithCascade().
     */
    EntityList findRelatedFk(Set<String> skipEntities);

    /** Remove the named Related Entity for the EntityValue from the persistent store
     * @param relationshipName String containing the relationship name which is the combination of relationship.title
     *   and relationship.related-entity-name as specified in the entity XML definition file
     */
    void deleteRelated(String relationshipName) throws EntityException;

    /** Delete this record plus records for all relationships specified. If any records exist for other relationships not specified
     * that depend on this record returns false and does not delete anything.
     *
     * Returns true if this and related records were deleted.
     */
    boolean deleteWithRelated(Set<String> relationshipsToDelete);

    /** Deletes this record and all records that depend on it, doing the same for each (cascading delete).
     * Deletes related records that depend on this record (records with a foreign key reference to this record).
     *
     * To clear the reference (set fields to null) instead of deleting records specify the entity names, related to this or any
     * related entity, in the clearRefEntities parameter.
     *
     * To check for records that should prevent a delete you can optionally pass a Set of entities names in the
     * validateAllowDeleteEntities parameter. If this is not null an exception will be thrown instead of deleting
     * any record for an entity NOT in that Set.
     *
     * WARNING: this may delete records you don't want to. Look at the nested relationships in the Entity Reference in the
     * Tools app to see what might might get deleted (anything with a type one relationship to this entity, or recursing
     * anything with a type one relationship to those).
     */
    void deleteWithCascade(Set<String> clearRefEntities, Set<String> validateAllowDeleteEntities);

    /**
     * Checks to see if all foreign key records exist in the database (records this record refers to).
     * Will attempt to create a dummy value (PK only) for those missing when specified insertDummy is true.
     *
     * @param insertDummy Create a dummy record using the provided fields
     * @return true if all FKs exist (or when all missing are created)
     */
    boolean checkFks(boolean insertDummy) throws EntityException;
    /** Compare this value to the database, adding messages about fields that differ or if the record doesn't exist to messages. */
    long checkAgainstDatabase(List<String> messages);

    /** Makes an XML Element object with an attribute for each field of the entity
     * @param document The XML Document that the new Element will be part of
     * @param prefix A prefix to put in front of the entity name in the tag name
     * @return org.w3c.dom.Element object representing this entity value
     */
    Element makeXmlElement(Document document, String prefix);

    /** Writes XML text with an attribute or CDATA element for each field of the entity. If dependents is true also
     * writes all dependent (descendant) records.
     * @param writer A Writer object to write to
     * @param prefix A prefix to put in front of the entity name in the tag name
     * @param dependentLevels Write dependent (descendant) records this many levels deep, zero for no dependents
     * @return The number of records written
     */
    int writeXmlText(Writer writer, String prefix, int dependentLevels);
    int writeXmlTextMaster(Writer pw, String prefix, String masterName);

    /** Get a Map with all non-null field values. If dependentLevels is greater than zero includes nested dependents
     * in the Map as an entry with key of the dependent relationship's short-alias or if no short-alias then the
     * relationship name (title + related-entity-name). Each dependent entity's Map may have its own dependent records
     * up to dependentLevels levels deep.*/
    Map<String, Object> getPlainValueMap(int dependentLevels);

    /** List getPlainValueMap() but uses a master definition to determine which dependent/related records to get. */
    Map<String, Object> getMasterValueMap(String name);
}
