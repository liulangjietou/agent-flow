package io.agentflow.expense.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * ExpenseSubmissionRejections 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface ExpenseSubmissionRejectionsMapper {
    /** 新增 record 所需的持久化事实。 */
    int record(
            @Param("tenantId") String tenantId,
            @Param("digest") String digest,
            @Param("reportId") String reportId,
            @Param("applicationId") String applicationId,
            @Param("userId") String userId,
            @Param("id") String id,
            @Param("applicationVersion") Long applicationVersion,
            @Param("financialVersion") Long financialVersion,
            @Param("roundNo") Integer roundNo,
            @Param("legalEntityId") String legalEntityId,
            @Param("departmentId") String departmentId,
            @Param("OCCUPIED") String OCCUPIED,
            @Param("code") String code,
            @Param("expectedTenantId") String expectedTenantId,
            @Param("attemptDigest") String attemptDigest);
}
