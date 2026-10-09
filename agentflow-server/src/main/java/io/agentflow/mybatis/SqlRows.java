package io.agentflow.mybatis;

import org.springframework.dao.support.DataAccessUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 将数据库投影交回原仓储恢复领域对象，并保留单行查询的基数约束。
 *
 * @author owlzhangfq@gmail.com
 */
public final class SqlRows {
    private SqlRows() {}

    /** 依次恢复结果，恢复失败直接传播并由外层事务回滚。 */
    public static <T> List<T> map(List<SqlRow> rows, Function<SqlRow, T> restore) {
        return rows.stream().map(restore).toList();
    }

    /** 保留历史证据按零起始行号校验顺序的行为。 */
    public static <T> List<T> map(List<SqlRow> rows, BiFunction<SqlRow, Integer, T> restore) {
        var restored = new ArrayList<T>(rows.size());
        for (int index = 0; index < rows.size(); index++)
            restored.add(restore.apply(rows.get(index), index));
        return restored;
    }

    /** 查询必须恰好返回一行；空行和多行维持原查询的 Spring 异常语义。 */
    public static <T> T single(List<T> rows) {
        return DataAccessUtils.nullableSingleResult(rows);
    }

    /** 原读模型以列名访问投影，保留大小写无关的列映射。 */
    public static List<Map<String, Object>> maps(List<SqlRow> rows) {
        return rows.stream().<Map<String, Object>>map(row -> row).toList();
    }
}
