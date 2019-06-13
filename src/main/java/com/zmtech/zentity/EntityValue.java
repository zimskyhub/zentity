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
     * 该方法支持通过实体关系名称取关系的实体
     * 格式为:"${title}${related-entity-name}"
     * 当取关系实体的时候相当于调用了
     * <code>findRelated(relationshipName, null, null, null, null)</code>:一对多
     * <code>findRelatedOne(relationshipName, null, null)</code> 一对一
     * @param name 字段名称或者关系的一对多 一对一名称
     * @return 字段值或者关系的实体
     */
    Object get(String name);

    /**
     * 取字段值
     * 不会检查字段是否存在
     * 不会取关系实体
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

    /**
     * 取实体原值
     * @param name 字段名称
     * @return 原值
     */
    Object getOriginalDbValue(String name);

    /**
     * 取关系实体 一对多
     * @param relationshipName 关系名称 该名称在实体定义relationship中
     * @param byAndFields 查询字段 可以为 null
     * @param orderBy 排序 可以为 null 可加选项 ASC DESC
     * @param useCache 是否使用缓存 默认值在实体定义中
     * @return 关系实体列表
     */
    EntityList findRelated(String relationshipName, Map<String, Object> byAndFields, List<String> orderBy,
                           Boolean useCache, Boolean forUpdate) throws EntityException;

    /**
     * 取关系实体 一对一
     * @param relationshipName 关系名称 该名称在实体定义relationship中
     * @param useCache 是否使用缓存 默认值在实体定义中
     * @return List of 关系实体
     */
    EntityValue findRelatedOne(String relationshipName, Boolean useCache, Boolean forUpdate) throws EntityException;

    /**
     * 取关系实体数量 一对多 一对一
     * @param relationshipName 关系名称 该名称在实体定义relationship中
     * @param useCache 是否使用缓存 默认值在实体定义中
     * @return 关系实体数量
     */
    long findRelatedCount(final String relationshipName, Boolean useCache);

    /**
     * 取所有外键关系到实体的关系实体
     * 该方法不会递归查询，只会查询直接关系的实体
     * 会跳过所有skip Entities
     * 可以用于验证级联删除
     * @param skipEntities 跳过的关系实体
     * @return 关系实体
     */
    EntityList findRelatedFk(Set<String> skipEntities);

    /**
     * 删除关系实体
     * @param relationshipName 关系名称 在实体定义中指定
     */
    void deleteRelated(String relationshipName) throws EntityException;

    /**
     * 删除关系实体
     * 删除此实体以及指定的所有关系的实体。 如果未指定的其他关系存在依赖于此实体的任何实体，则返回false并且不删除任何内容。
     * @return 删除:true 未删除:false
     */
    boolean deleteWithRelated(Set<String> relationshipsToDelete);

    /**
     * 实体级联删除
     * 删除此实体以及依赖于此实体的所有实体，对每个实体执行相同的操作（级联删除）。
     * 删除依赖于此实体的相关实体（具有对此实体的外键引用的实体）。
     * 要清除关系（将字段设置为null）而不是删除记录，请指定与此或任何相关的实体名称相关实体，在clearRefEntities参数中。
     * 要检查应该阻止删除的记录，您可以选择传递一组实体名称validateAllowDeleteEntities参数。
     * 如果不为null，则抛出异常而不是删除不在该集合中的实体的任何记录。
     * 警告：这可能会删除您不想要的记录。
     * 查看实体参考中的嵌套关系工具应用程序，以查看可能会被删除的内容（与此实体具有第一类关系的任何内容，或递归与那些有第一类关系的任何东西）。
     * @param clearRefEntities 清除关系实体列表
     * @param validateAllowDeleteEntities 验证允许删除的实体名称列表
     */
    void deleteWithCascade(Set<String> clearRefEntities, Set<String> validateAllowDeleteEntities);

    /**
     * 检查外键
     * 检查数据库中是否存在所有外键记录（记录此记录所引用的记录）。
     * 当指定的insertDummy为true时，将尝试为缺少的值创建一个虚拟值（仅限PK）。
     * @param insertDummy 是否使用提供的字段创建虚拟记录
     * @return 所有外键存在:true 不存在:false
     */
    boolean checkFks(boolean insertDummy) throws EntityException;

    /**
     * 检查数据库
     * 将此值与数据库进行比较，添加有不同字段的消息或消息中是否存在记录。
     * @param messages 消息列表
     * @return 字段不同的数量
     */
    long checkAgainstDatabase(List<String> messages);

    /**
     * 创建xml 对象
     * 使用实体的每个字段的属性创建XML Element对象
     * @param document 新元素将成为XML document 元素的一部分
     * @param prefix 放在标记名称中实体名称前面的前缀
     * @return 表示此实体值的org.w3c.dom.Element对象
     */
    Element makeXmlElement(Document document, String prefix);

    /**
     * 写入实体
     * 主要用于初始化数据
     * 为实体的每个字段写入带有属性或CDATA元素的XML文本。 如果dependents为true，则还会写入所有依赖（后代）记录
     * @param writer 输出
     * @param prefix 放在标记名称中实体名称前面的前缀
     * @param dependentLevels 写入依赖（后代）记录的级别深度，零没有依赖
     * @return 写入的记录数
     */
    int writeXmlText(Writer writer, String prefix, int dependentLevels);
    int writeXmlTextMaster(Writer writer, String prefix, String masterName);

    /**
     * 取费用字段Map
     * 获取包含所有非空字段值的地图。
     * 如果dependentLevels大于零，则包含Map中的嵌套依赖项作为具有依赖关系的短别名的键的条目，或者如果没有短别名，则关系名称（标题+相关实体名称）。
     * 每个从属实体的Map可以有自己的依赖记录最深层的依赖级别。
     * @param dependentLevels 依赖级别
     * @return 字段Map
     * */
    Map<String, Object> getPlainValueMap(int dependentLevels);

    /**
     * 列出getPlainValueMap（）但使用主定义来确定要获取的依赖/相关记录。
     * */
    Map<String, Object> getMasterValueMap(String name);
}
