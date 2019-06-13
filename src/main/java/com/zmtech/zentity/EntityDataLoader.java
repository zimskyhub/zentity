package com.zmtech.zentity;

import java.util.List;
import java.util.Map;
import java.util.Set;

@SuppressWarnings("unused")
public interface EntityDataLoader {

    /**
     * 加载文件
     * 要加载的数据文件的位置。 可以多次调用来加载多个文件。
     * @param location 文件位置
     * @return 当前对象
     */
    EntityDataLoader location(String location);

    /**
     * 加载文件
     * 要加载的文件的位置列表。 将被添加到运行列表中，因此可以多次调用并与location（）方法一起调用。
     * @param locationList 文件位置
     * @return 当前对象
     */
    EntityDataLoader locationList(List<String> locationList);

    /**
     * 加载 xml 文本
     * @param xmlText xml文本
     * @return 当前对象
     */
    EntityDataLoader xmlText(String xmlText);

    /**
     * 加载 csv 文本
     * @param csvText csv文本
     * @return 当前对象
     */
    EntityDataLoader csvText(String csvText);

    /**
     * 加载 json 文本
     * @param jsonText json文本
     * @return 当前对象
     */
    EntityDataLoader jsonText(String jsonText);

    /**
     * 加载列表数据
     * 一组数据类型，用于匹配组件数据目录和entity-facade.load-data元素中的候选文件。
     * @param dataTypes 数据列表
     * @return 当前对象
     */
    EntityDataLoader dataTypes(Set<String> dataTypes);

    /**
     * 与dataTypes一起使用; 要从中加载数据的组件名称列表。 如果没有指定将从所有组件加载。
     *  */
    EntityDataLoader componentNameList(List<String> componentNames);

    /**
     * 设置数据加载事务超时时间
     * 数据加载的事务超时时间（以秒为单位）。 默认为3600，即1小时。
     * @param timeout 超时时间（秒）
     * @return 当前对象
     */
    EntityDataLoader transactionTimeout(int timeout);

    /**
     * 设置尝试插入（这个名字我喜欢）
     * @param useTryInsert 如果为true而不是对文件中的每个值进行查询，则只会尝试插入它，如果失败，则会尝试更新现有记录。
     *                     适用于db中大多数值都是新的情况。
     * @return 当前对象.
     */
    EntityDataLoader useTryInsert(boolean useTryInsert);

    /**
     * 设置虚拟外键
     * @param dummyFks 如果为true将检查每个值的所有外键关系，如果缺少任何值，则创建一个带有主键的新记录，以避免出现外键约束错误。
     *                 这只应在您确信这些数据的其余部分时使用 将从其他地方加载新的fk记录，以避免出现孤立的记录。
     * @return 当前对象.
     */
    EntityDataLoader dummyFks(boolean dummyFks);

    /**
     * 设置禁用eca规则
     * @param disable 设置为true以禁用Entity Facade ECA规则（仅适用于此导入，不会影响系统中发生的其他事情）
     * @return 当前对象.
     */
    EntityDataLoader disableEntityEca(boolean disable);

    /**
     * 设置禁用审核日志
     * @param disable 设置为true以禁用
     * @return 当前对象.
     */
    EntityDataLoader disableAuditLog(boolean disable);

    /**
     * 设置禁用创建外键
     * @param disable 设置为true以禁用
     * @return 当前对象.
     */
    EntityDataLoader disableFkCreate(boolean disable);

    /**
     * 设置禁用数据反馈
     * @param disable 设置为true以禁用
     * @return 当前对象.
     */
    EntityDataLoader disableDataFeed(boolean disable);

    /**
     * 设置cvs分割符
     * @param delimiter 分割符
     * @return 当前对象.
     */
    EntityDataLoader csvDelimiter(char delimiter);

    /**
     * 设置cvs备注
     * @param commentStart cvs备注
     * @return 当前对象.
     */
    EntityDataLoader csvCommentStart(char commentStart);

    /**
     * 设置cvs引用符
     * @param quoteChar cvs引用符
     * @return 当前对象.
     */
    EntityDataLoader csvQuoteChar(char quoteChar);

    /**
     * cvs 实体名称
     * 对于CSV文件，请使用此名称（实体或服务名称），而不是在文件的第一行中查找
     * @param entityName 实体名称
     * @return 当前对象.
     */
    EntityDataLoader csvEntityName(String entityName);

    /**
     * cvs 字段名称
     * 对于CSV文件，请使用这些字段名称，而不是在文件的第二行中查找它们
     * @param fieldNames 字段名称
     * @return 当前对象.
     */
    EntityDataLoader csvFieldNames(List<String> fieldNames);
    /**
     * 设置默认值
     * 要加载的所有记录的默认值（如果适用于给定的实体）
     * @param defaultValues 默认值
     * @return 当前对象.
     */
    EntityDataLoader defaultValues(Map<String, Object> defaultValues);

    /**
     * 数据检查
     * 仅检查数据以匹配数据库中的记录。 报告数据库中不存在的记录以及与具有匹配主键的记录的任何差异。
     * @return 关于文件中的数据与数据库中的数据之间的每个比较的消息列表.
     */
    List<String> check();

    /**
     * 数据检查
     * 仅检查数据以匹配数据库中的记录。 报告数据库中不存在的记录以及与具有匹配主键的记录的任何差异。
     * @return 检查数据数量.
     */
    long check(List<String> messageList);

    /**
     * 加载数据到数据库
     * @return 加载的数据数量
     */
    long load();

    /**
     * 使用数据文件中的所有值创建 EntityList
     * @return XML文档中的值的EntityValue对象列表.
     */
    EntityList list();
}
