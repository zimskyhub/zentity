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
package com.zmtech.zentity.entity;

import com.zmtech.zentity.util.MNode;

import java.util.List;
import java.util.Map;

/**
 * 此类用于使用和丢弃的动态视图。
 * EntityFind上存在一种特殊方法，用于接受EntityDynamicView而不是entityName。
 * 此处的方法为方便起见，返回对其自身（this）的引用。
 */
@SuppressWarnings("unused")
public interface EntityDynamicView {

    /**
     * 为动态视图设置名称。
     * 如果不使用则默认为“DynamicView”。
     * @param entityName 实体名称
     * @return 当前对象
     */
    EntityDynamicView setEntityName(String entityName);

    /**
     * 为动态视图添加成员实体。
     * @param entityAlias 实体别名
     * @param entityName 实体名称
     * @param joinFromAlias 成员实体别名
     * @param joinOptional 加入方式
     * @param entityKeyMaps 对应主键
     * @return 当前对象
     */
    EntityDynamicView addMemberEntity(String entityAlias, String entityName, String joinFromAlias,
                                      Boolean joinOptional, Map<String, String> entityKeyMaps);

    /**
     * 为动态视图添加关系成员。
     * @param entityAlias 实体别名
     * @param joinFromAlias 成员实体别名
     * @param joinOptional 加入方式
     * @return 当前对象
     */
    EntityDynamicView addRelationshipMember(String entityAlias, String joinFromAlias, String relationshipName,
                                            Boolean joinOptional);

    /**
     * 取动态视图的成员实体。
     * @return 成员实体列表
     */
    List<MNode> getMemberEntityNodes();

    /**
     * 添加引用。
     * @param entityAlias 引用实体
     * @param prefix 前缀
     * @return 当前对象
     */
    EntityDynamicView addAliasAll(String entityAlias, String prefix);

    /**
     * 添加引用。
     * @param entityAlias 引用实体
     * @param name 引用名称
     * @return 当前对象
     */
    EntityDynamicView addAlias(String entityAlias, String name);

    /**
     * 添加引用，完整详细信息。
     * 除entityAlias和name之外，所有参数都可以为null
     * @param entityAlias 引用实体
     * @param name 引用名称
     * @param field 字段
     * @param function 函数,用于字段的计算，比如说 sum max min
     */
    EntityDynamicView addAlias(String entityAlias, String name, String field, String function);

    /**
     * 添加关系实体
     * 除entityAlias和name之外，所有参数都可以为null
     * @param type 关系类型 一对一:one, 一对多: many
     * @param title 关系标题
     * @param relatedEntityName 关系实体名称
     * @param entityKeyMaps 关系实体主键
     */
    EntityDynamicView addRelationship(String type, String title, String relatedEntityName,
                                      Map<String, String> entityKeyMaps);
}
