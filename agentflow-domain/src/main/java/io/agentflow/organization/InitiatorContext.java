package io.agentflow.organization;

import java.util.UUID;

/**
 * 提交时冻结的发起任职上下文；身份及组织名称由服务端取得，不随目录修改覆盖历史。
 * @author owlzhangfq@gmail.com
 */
public record InitiatorContext(UUID appointmentId, UUID personId, String subject, long directoryRevision,
                               UUID legalEntityId, String legalEntityName, UUID departmentId,
                               String departmentName, UUID positionId, String positionName) { }
