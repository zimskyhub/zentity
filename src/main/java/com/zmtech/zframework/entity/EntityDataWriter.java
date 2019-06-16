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
package com.zmtech.zframework.entity;

import java.io.Writer;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * 用于将XML实体数据从数据库写入writer
 * xml 文件必须有根元素 entity-facade-xml
 */
@SuppressWarnings("unused")
public interface EntityDataWriter {

    FileType XML = FileType.XML;
    FileType JSON = FileType.JSON;

    enum FileType { XML, JSON }

    EntityDataWriter fileType(FileType ft);

    /**
     * 设置要查询和导出的实体的名称。 通过多次调用this或entityNames（）来查询数据并按实体的顺序从实体中导出数据
     * @param entityName 实体名称
     * @return 当前对象.
     */
    EntityDataWriter entityName(String entityName);

    /**
     * 设置要查询和导出的实体名称列表。 查询数据并按照在此列表中设置的顺序从实体导出以及对此实体或其他名称的调用（）.
     * @param entityNames 实体名称列表
     * @return 当前对象.
     */
    EntityDataWriter entityNames(List<String> entityNames);

    /**
     * 从所有实体写入数据。
     * 设置时，排除其他实体名称而不是包括在内
     */
    EntityDataWriter allEntities();

    /**
     * 设置是否应该写入每条记录的依赖记录 如果设置将默认包含2个依赖项级别，请使用 dependentLevels（）设置不同数量的级别
     * @param dependents 依赖标记
     * @return 当前对象.
     */
    EntityDataWriter dependentRecords(boolean dependents);

    /**
     * 设置写入记录所包含的依赖级别数。
     * 如果设置 dependentRecords 将被视为true。
     * 如果设置了dependentRecords 但未设置级别限制，则将写入所有找到的级别（可能不是所需的级别）。
     * @param levels 依赖级别
     * @return 当前对象.
     */
    EntityDataWriter dependentLevels(int levels);

    /**
     * 设置主定义的名称，应用于具有匹配主定义的所有写入实体，否则仅写入单个记录，或者如果设置 dependentRecords 或 dependentLevels选项，则应用于相关记录。
     * @param masterName 主定义的名称
     * @return 当前对象.
     */
    EntityDataWriter master(String masterName);

    /**
     * 适用字段名称的映射，用于过滤结果的键值对。
     * 每个名称/值仅用于具有与名称匹配的字段的实体。
     * @param filterMap 进行过滤的 名称/值
     * @return 当前对象.
     */
    EntityDataWriter filterMap(Map<String, Object> filterMap);

    /**
     * 字段名称按顺序排序。
     * 每个名称仅用于具有与名称匹配的字段的实体。
     * 可以多次调用。
     * 每个条目可以是以逗号分隔的字段名称列表。
     * @param orderByList 列表，其中包含要按顺序排列的字段名称
     * @return 当前对象.
     */
    EntityDataWriter orderBy(List<String> orderByList);

    /**
     * 开始日期（作用于字段lastUpdatedStamp 必须大于或等于（＆gt; =）fromDate）。
     * @param fromDate 开始日期
     * @return 当前对象.
     */
    EntityDataWriter fromDate(Timestamp fromDate);

    /** 截止日期（作用于字段lastUpdatedStamp 必须小于（＆lt;）thruDate）。
     * @param thruDate 截止日期
     * @return 当前对象.
     */
    EntityDataWriter thruDate(Timestamp thruDate);

    /**
     * 将所有结果写入单个文件.
     * @param filename 文件路径
     * @return 写入数量
     */
    int file(String filename);

    /**
     * 压缩文件.
     * @param filenameWithinZip 压缩文件名称
     * @param zipFilename 压缩文件名称
     * @return 写入数量
     */
    int zipFile(String filenameWithinZip, String zipFilename);

    /**
     * 将结果写入指定目录中
     * @param path 目录路径
     * @return 写入数量
     */
    int directory(String path);

    int zipDirectory(String pathWithinZip, String zipFilename);

    /** 将结果写入Writer.
     * @param writer Writer
     * @return 写入数量
     */
    int writer(Writer writer);
}
