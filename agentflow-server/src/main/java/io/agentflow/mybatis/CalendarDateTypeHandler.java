package io.agentflow.mybatis;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;

/**
 * 日历日期按 JDBC 4.2 读取，避免 java.sql.Date 的历史历法转换改变公元初年的日期。
 *
 * @author owlzhangfq@gmail.com
 */
public final class CalendarDateTypeHandler extends BaseTypeHandler<Object> {
    /** 保留已确定的 JDBC 日期对象，不经过时区或时间戳转换。 */
    @Override
    public void setNonNullParameter(
            PreparedStatement statement, int index, Object value, JdbcType type)
            throws SQLException {
        statement.setObject(index, value);
    }

    /** 按列名读取 ISO 日历日期。 */
    @Override
    public Object getNullableResult(ResultSet rows, String column) throws SQLException {
        return rows.getObject(column, LocalDate.class);
    }

    /** 按列序号读取 ISO 日历日期。 */
    @Override
    public Object getNullableResult(ResultSet rows, int column) throws SQLException {
        return rows.getObject(column, LocalDate.class);
    }

    /** 存储过程日期投影遵守相同的读取契约。 */
    @Override
    public Object getNullableResult(CallableStatement statement, int column) throws SQLException {
        return statement.getObject(column, LocalDate.class);
    }
}
