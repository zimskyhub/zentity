package com.zmtech.zentity.entity;
import com.zmtech.zentity.etl.SimpleEtl;
import com.zmtech.zentity.exception.EntityException;
import com.zmtech.zentity.util.MNode;
import org.w3c.dom.Element;

import java.sql.Connection;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

/** ZEntity数据库操作的Facade。*/
@SuppressWarnings("unused")
public interface EntityFacade {

    /**
     * 取实体数据源工厂。
     * 这对于非SQL数据库来说非常有用，可以访问底层详细信息。
     * @param groupName 数据源分组信息
     */
    EntityDatasourceFactory getDatasourceFactory(String groupName);

    /**
     * 取一个实体条件工厂，该对象可用于创建和组装用于查找的条件。
     * @return Facade的活动EntityConditionFactory对象。
     */
    EntityConditionFactory getConditionFactory();

    /**
     * 创建一个空实体,并不会持久化。
     * @param entityName 实体定义名称.
     * @return 实体对象.
     */
    EntityValue makeValue(String entityName);
    
    /**
     * 创建一个EntityFind对象，该对象可用于指定其他选项，然后执行一个或多个查找（查询）。
     * @param entityName 实体定义名称.
     * @return 实体查询对象.
     */
    EntityFind find(String entityName);

    /**
     * 创建一个EntityFind对象，该对象可用于指定其他选项，然后执行一个或多个查找（查询）。
     * @param entityFindNode 实体定义Node.
     * @return 实体查询对象.
     */
    EntityFind find(MNode entityFindNode);

    /**
     * 用于处理实体REST请求，但更常用作执行实体操作的简单方法。
     * @param operation 操作符，可以进行/查找，发布/创建，放置/存储，修补/更新或删除/删除。
     * @param entityPath 第一个元素应该是实体名称或短别名，后跟（可选地取决于操作）实体的PK字段按照它们在实体定义中出现的顺序，可选地由（一个或多个）关系名称或短名称和 然后是相关实体的PK值，不包括关系中定义的任何PK字段。
     * @param parameters 根据操作使用的额外参数的映射。
     *                   对于查找操作，这些可以是EntityFind.searchFormInputs（）方法处理的任何参数。
     *                   对于创建，更新，存储和删除操作，这些参数用于非PK字段，并作为PK字段值的实体路径的替代。
     *                   对于查找操作，还支持“dependents”参数，如果为true，则将获取实体路径中引用的记录的相关值。
     * @param masterNameInPath 如果为true，则第二个entityPath条目必须是主实体定义的名称.
     */
    Object rest(String operation, List<String> entityPath, Map parameters, boolean masterNameInPath);

    /**
     * 使用SQL执行数据库查询，并将结果作为实体的EntityList返回选定的列映射到列出的字段。
     * @param sql SQL语句.
     * @param sqlParameterList 与SQL中的（？）对应的参数值.
     * @param entityName 映射到的实体的名称。
     * @param fieldList 实体字段名称列表，以便它们与查询中选择的列匹配。
     * @return 具有查询结果的EntityListIterator。
     */
    EntityListIterator sqlFind(String sql, List<Object> sqlParameterList, String entityName, List<String> fieldList);

    /**
     * 查找数据文档，可以将其转换为JSON格式文档。
     * 用于通过数据搜索功能进行搜索，以及使用数据反馈功能向其他系统提供数据。
     * @param dataDocumentId 用于查找DataDocument和相关记录（DataDocument *实体）。
     * @param condition  可选条件。
     * @param fromUpdateStamp lastUpdatedStamp字段 开始时间
     * @param thruUpdatedStamp lastUpdatedStamp字段 截止时间
     * @return 包含以下内容的列表:
     *      - _index = DataDocument.indexName                                   索引名称
     *      - _type = dataDocumentId                                            数据文档Id
     *      - _id = 来自主实体的pk字段值，下划线分隔
     *      - _timestamp = 创建文档时的时间戳
     *      - 主实体的映射（以primaryEntityName作为键）
     *      - 来自具有别名字段的DataDocumentField记录的每个相关实体的嵌套地图列表 (以关系名称作为key)
     *
     */
    ArrayList<Map> getDataDocuments(String dataDocumentId, EntityCondition condition, Timestamp fromUpdateStamp,
                                    Timestamp thruUpdatedStamp);

    /**
     * 查找数据文档，可以将其转换为JSON格式文档。
     * 这类似于getDataDocuments（）方法，除了使用dataFeedId查找dataDocumentId。
     * @param dataFeedId 数据反馈Id.
     * @param fromUpdateStamp lastUpdatedStamp字段 开始时间
     * @param thruUpdatedStamp lastUpdatedStamp字段 截止时间
     * @return 数据文档Map列表:
     */
    ArrayList<Map> getDataFeedDocuments(String dataFeedId, Timestamp fromUpdateStamp, Timestamp thruUpdatedStamp);

    /**
     * 从具有指定序列名称的序列中获取下一个保证的唯一seq id;
     * 如果命名序列不存在，则将创建它。
     *
     * @param seqName 序列名称
     * @param staggerMax 错开序列ID的最大值，如果1，序列将增加1，否则当前序列ID将增加1和staggerMax之间的值
     * @param bankSize 从数据库中获取的值“bank”的大小（默认为1）
     * @return 指定序列名称的下一个seq id
     */
    String sequencedIdPrimary(String seqName, Long staggerMax, Long bankSize);

    /**
     * 取实体的组名
     * @param entityName 实体名称
     * @return 实体组名
     */
    String getEntityGroupName(String entityName);

    /**
     * 取数据库连接对象
     * 如果要直接执行JDBC操作，请使用此选项来获取连接。
     * 此连接将在活动事务中登记。
     * @param groupName @param groupName要获取连接的实体组的名称。
     *                  对应于实体。@ group属性和moqui-conf数据源。
     * @throws EntityException 实体操作异常
     */
    Connection getConnection(String groupName) throws EntityException;

    // ======= 导入/导出（XML，CSV等）相关方法 ========

    /**
     * 创建一个用于将XML或CSV实体数据加载到数据库或EntityList中的对象。
     * 这些文件来自特定位置，文本已从某处读取，或者通过搜索所有组件数据目录和entity-facade.load-data元素来查找与指定类型集中的类型匹配的实体数据文件。
     * XML文档应具有<entity-facade-xml type ="seed">根元素。
     * type属性将用于确定是否应该通过加载文件是否与为加载器上的数据类型指定的值匹配来加载文件。
     * @return EntityDataLoader对象
     */
    EntityDataLoader makeDataLoader();

    /**
     * 创建Writer
     * 用于将XML实体数据从数据库写入writer。
     * XML文档应具有<entity-facade-xml type ="seed">根元素。
     * @return EntityDataWriter对象
     */
    EntityDataWriter makeDataWriter();

    /**
     * 创建Etl
     * 用于将XML实体数据从数据库写入writer。
     * XML文档应具有<entity-facade-xml type ="seed">根元素。
     * @return  SimpleEtl.Loader对象
     */
    SimpleEtl.Loader makeEtlLoader();

    /**
     * 创建一个EntityValue并使用指定XML元素中的数据（属性和子元素）填充它。
     * @param element 表示实体的单个值/记录的XML DOM元素。
     * @return 实体对象
     */
    EntityValue makeValue(Element element);

    Calendar getCalendarForTzLc();
}
