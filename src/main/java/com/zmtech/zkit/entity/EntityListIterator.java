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
package com.zmtech.zkit.entity;

import com.zmtech.zkit.exception.EntityException;

import java.io.Writer;
import java.util.ListIterator;

/**
 * 用于处理索引数据库结果的实体索引列表迭代器
 */
@SuppressWarnings("unused")
public interface EntityListIterator extends ListIterator<EntityValue> {

    /**
     * 关闭迭代器
     * 关闭基础ResultSet和Connection。 使用EntityListIterator时，必须在最后调用此方法。
     * */
    void close() throws EntityException;

    /** 将索引位置设置为刚好在最后一个结果之后，以便使用 previous（）方法将返回最后一个结果 */
    void afterLast() throws EntityException;

    /** 将索引位置设置为第一个结果之前，以便使用 next（）方法将返回第一个结果 */
    void beforeFirst() throws EntityException;

    /** 将索引位置设置为最后结果; 如果结果集为空，则返回false */
    boolean last() throws EntityException;

    /** 将索引位置设置为第一个结果; 如果结果集为空，则返回false */
    boolean first() throws EntityException;

    /**
     * 取当前实体
     * 注意：调用此方法会返回当前值，但使用 next（）方法或previous（）方法也是返回当前值，因此调用其中一个AND此方法将导致值创建两次
     */
    EntityValue currentEntityValue() throws EntityException;

    /** 当前迭代器的索引位置 */
    int currentIndex() throws EntityException;

    /**
     * 相当于ResultSet.absolute方法
     * 如果rowNum为正，则转到相对于列表开头的那个位置;
     * 如果rowNum为负数，则转到相对于列表末尾的那个位置;
     * rowNum为1与first（）相同; rowNum为-1与last（）相同
     */
    boolean absolute(int rowNum) throws EntityException;

    /**
     * 相当于ResultSet.relative方法
     * 如果行为正，则相对于当前位置前进;
     * 如果行为负数，则相对于当前位置向后移动;
     */
    boolean relative(int rows) throws EntityException;

    /**
     * 是否含有下一个实体
     * 请注意：由于JDBC ResultSet接口的性质，此方法可能效率很低; 最好只使用next（）直到它返回null。
     */
    @Override boolean hasNext();

    /**
     * 是否含有前一个实体
     * 请注意：由于JDBC ResultSet接口的性质，此方法可能效率很低; 最好只使用previous（）直到它返回null。
     */
    @Override boolean hasPrevious();

    /**
     * 将索引移动到下一个位置并返回该位置的EntityValue对象;
     * 如果没有，则返回null。
     */
    @Override EntityValue next();

    /** 返回下一个结果的索引，但不保证会有下一个结果 */
    @Override int nextIndex();

    /**
     * 将指针移动到上一个位置并返回该位置的EntityValue对象; 如果没有，则返回null。
     */
    @Override EntityValue previous();

    /** 返回先前结果的索引，但不保证将存在先前的结果 */
    @Override int previousIndex();


    void setFetchSize(int rows) throws EntityException;

    EntityList getCompleteList(boolean closeAfter) throws EntityException;

    /**
     * 取从start开始并包含最多数字元素的部分结果列表.
     * Start是一个基于值的值，即1是第一个元素。
     */
    EntityList getPartialList(int offset, int limit, boolean closeAfter) throws EntityException;

    /**
     * 为每个记录的每个字段写入带有属性或CDATA元素的XML文本。
     * 如果dependents为true，则还会写入所有依赖（后代）记录。
     * @param writer 要写入的Writer对象
     * @param prefix 实体名称的前缀
     * @param dependentLevels 写入依赖（后代）记录这么多级深，零代表没有
     * @return 写入的记录数
     */
    int writeXmlText(Writer writer, String prefix, int dependentLevels);
    int writeXmlTextMaster(Writer writer, String prefix, String masterName);
}
