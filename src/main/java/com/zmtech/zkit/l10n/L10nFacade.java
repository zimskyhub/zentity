package com.zmtech.zkit.l10n;

import java.sql.Timestamp;
import java.util.Locale;
import java.util.TimeZone;

/** 用于本地化（l10n）功能，如本地化消息。 */
public interface L10nFacade {

    /**
     * 使用当前语言环境（请参阅ec.user.getLocale（）方法）根据moqui.basic.LocalizedMessage实体中的数据本地化消息。
     * 本地化消息可能使用$ {}语法插入变量，当通过ec.resource.expand（）调用它时。
     * 这里的方法是原始消息是应用程序主要语言中的实际消息。
     * 与显式/人工属性键的方法相比，这减少了重复消息的问题。
     * 较长的消息（超过255个字符）应使用人工消息密钥，实际值始终来自数据库。
     */
    String localize(String original);
    /** 使用给定的Locale而不是当前用户来本地化String。 */
    String localize(String original, Locale locale);

    /**
     * 格式化的货币金额。
     * @param amount 表示金额的对象应该是Number的子类。
     * @param uomId 需要uomId（ISO货币代码）。
     * @param fractionDigits 要显示的小数点后的位数。
     *                      如果null默认为java.util.Currency.defaultFractionDigits（）为uomId中指定货币定义的数字。
     * @return 格式化的货币金额。
     */
    String formatCurrency(Object amount, String uomId, Integer fractionDigits);
    String formatCurrency(Object amount, String uomId);
    String formatCurrency(Object amount, String uomId, Integer fractionDigits, Locale locale);

    /**
     * 根据货币指定的位数和舍入方法舍入货币。
     * @param amount 要舍入的BigDecimal中的数量。
     * @param uomId 需要货币uomId（ISO货币代码）
     * @param precise 一个布尔值，指示是否应使用附加数字处理货币
     * @param roundingMethod 要使用的舍入方法（例如BigDecimal.ROUND_HALF_UP）
     * @return 四舍五入的货币金额。
     */
    java.math.BigDecimal roundCurrency(java.math.BigDecimal amount, String uomId, boolean precise, int roundingMethod);
    java.math.BigDecimal roundCurrency(java.math.BigDecimal amount, String uomId, boolean precise);
    java.math.BigDecimal roundCurrency(java.math.BigDecimal amount, String uomId);

    /**
     * 使用给定的格式字符串格式化数字，时间戳，日期，时间或日历对象。
     * 如果未指定格式字符串，则将使用用户区域设置和时区的默认值。
     * @param value 要格式化的值。 必须是数字，时间戳，日期，时间或日历对象。
     * @param format 格式字符串，用于指定如何格式化值。
     * @return 作为String的值根据格式字符串格式化。
     */
    String format(Object value, String format);
    String format(Object value, String format, Locale locale, TimeZone tz);

    java.sql.Time parseTime(String input, String format);
    java.sql.Date parseDate(String input, String format);
    Timestamp parseTimestamp(String input, String format);
    Timestamp parseTimestamp(String input, String format, Locale locale, TimeZone timeZone);
    java.util.Calendar parseDateTime(String input, String format);

    java.math.BigDecimal parseNumber(String input, String format);
}
