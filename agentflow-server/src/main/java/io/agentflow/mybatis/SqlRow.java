package io.agentflow.mybatis;

import org.springframework.util.LinkedCaseInsensitiveMap;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;

/**
 * MyBatis 的内部列投影；聚合恢复和 JSON 校验继续由仓储负责。 列名大小写由驱动决定，按不区分大小写的名称读取以兼容 PostgreSQL 和 H2。
 *
 * @author owlzhangfq@gmail.com
 */
public final class SqlRow extends LinkedCaseInsensitiveMap<Object> {
    /** 创建供 MyBatis 自动填充的空行。 */
    public SqlRow() {
        super(Locale.ROOT);
    }

    /** 读取可空字符串。 */
    public String getString(String column) {
        Object value = getObject(column);
        return value == null ? null : value.toString();
    }

    /** 兼容旧单列投影的 JDBC 一起始序号读取。 */
    public String getString(int column) {
        return getString(columnName(column));
    }

    /** 读取按序号定位的单列整数投影。 */
    public int getInt(int column) {
        return getInt(columnName(column));
    }

    /** 读取按序号定位的单列长整数投影。 */
    public long getLong(int column) {
        return getLong(columnName(column));
    }

    /** 读取按序号定位的单列时间投影。 */
    public Timestamp getTimestamp(int column) {
        return getTimestamp(columnName(column));
    }

    private String columnName(int column) {
        if (column < 1 || column > size())
            throw new IllegalArgumentException("Invalid persistence column index: " + column);
        return keySet().stream().skip(column - 1L).findFirst().orElseThrow();
    }

    /** 读取整数；SQL NULL 与原 JDBC 基本类型读取一致，返回零。 */
    public int getInt(String column) {
        Number value = getObject(column, Number.class);
        return value == null ? 0 : value.intValue();
    }

    /** 读取长整数；需要区分空值时使用 getObject。 */
    public long getLong(String column) {
        Number value = getObject(column, Number.class);
        return value == null ? 0 : value.longValue();
    }

    /** 读取布尔列。 */
    public boolean getBoolean(String column) {
        Object value = getObject(column);
        return value instanceof Boolean flag
                ? flag
                : value instanceof Number number && number.intValue() != 0;
    }

    /** 读取精确金额，不经过浮点转换。 */
    public BigDecimal getBigDecimal(String column) {
        return getObject(column, BigDecimal.class);
    }

    /** 读取数据库时间，保留微秒精度和带时区时间所表示的时刻。 */
    public Timestamp getTimestamp(String column) {
        Object value = getObject(column);
        if (value == null || value instanceof Timestamp) return (Timestamp) value;
        if (value instanceof OffsetDateTime dateTime) return Timestamp.from(dateTime.toInstant());
        if (value instanceof Instant instant) return Timestamp.from(instant);
        if (value instanceof LocalDateTime dateTime) return Timestamp.valueOf(dateTime);
        throw conversion(column, Timestamp.class);
    }

    /** 读取不带时区的日历日期。 */
    public Date getDate(String column) {
        Object value = getObject(column);
        if (value == null || value instanceof Date) return (Date) value;
        if (value instanceof LocalDate date) return Date.valueOf(date);
        throw conversion(column, Date.class);
    }

    /** 区分缺失投影列和 SQL NULL，避免损坏数据被当作空值恢复。 */
    public Object getObject(String column) {
        if (!containsKey(column))
            throw new IllegalStateException("Missing persistence column: " + column);
        return get(column);
    }

    /** 读取可空类型；适配两个驱动对数值、日期和带时区时间的不同表示。 */
    public <T> T getObject(String column, Class<T> type) {
        Object value = getObject(column);
        if (value == null || type.isInstance(value)) return type.cast(value);
        if (value instanceof Number number) {
            if (type == Integer.class) return type.cast(number.intValue());
            if (type == Long.class) return type.cast(number.longValue());
        }
        if (type == OffsetDateTime.class && value instanceof Timestamp timestamp) {
            return type.cast(timestamp.toInstant().atOffset(ZoneOffset.UTC));
        }
        if (type == LocalDate.class && value instanceof Date date)
            return type.cast(date.toLocalDate());
        throw conversion(column, type);
    }

    private static IllegalStateException conversion(String column, Class<?> type) {
        return new IllegalStateException(
                "Persistence column " + column + " cannot be read as " + type.getSimpleName());
    }
}
