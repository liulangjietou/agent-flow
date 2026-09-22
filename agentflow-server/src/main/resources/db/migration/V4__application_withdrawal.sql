-- 已有轮次内容不变，仅允许记录发起人的撤回结论。
ALTER TABLE approval_submission_round DROP CONSTRAINT ck_submission_conclusion;
ALTER TABLE approval_submission_round ADD CONSTRAINT ck_submission_conclusion CHECK (
    (status = 'IN_APPROVAL' AND reason IS NULL AND completed_by IS NULL AND completed_at IS NULL)
    OR (status IN ('RETURNED', 'REJECTED', 'APPROVED', 'WITHDRAWN') AND completed_by IS NOT NULL AND completed_at IS NOT NULL)
);
