package io.agentflow.jdbc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * H2 与 PostgreSQL 微秒时间列的投影；原始 JSON 凭证仍保留纳秒。
 * @author owlzhangfq@gmail.com
 */
public final class JdbcTimestampPrecision {
    private static final int NANOS_PER_MICRO = 1_000;
    private static final int HALF_MICRO_NANOS = NANOS_PER_MICRO / 2;

    private JdbcTimestampPrecision() { }

    /** 与 TIMESTAMP(6) 的舍入一致，包括进位至下一秒，兼容已有数据库时间元数据。 */
    public static Instant roundedToMicros(Instant value) {
        var truncated = value.truncatedTo(ChronoUnit.MICROS);
        return value.getNano() % NANOS_PER_MICRO >= HALF_MICRO_NANOS ? truncated.plusNanos(NANOS_PER_MICRO) : truncated;
    }
}
