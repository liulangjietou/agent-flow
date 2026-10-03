-- 父调用停止可取消尚未决定的子轮次；原快照和已形成的结论不回填或改写。
ALTER TABLE approval_submission_round DROP CONSTRAINT ck_submission_conclusion;
ALTER TABLE approval_submission_round ADD CONSTRAINT ck_submission_conclusion CHECK (
    (status = 'IN_APPROVAL' AND reason IS NULL AND completed_by IS NULL AND completed_at IS NULL)
    OR (status IN ('RETURNED', 'REJECTED', 'APPROVED', 'WITHDRAWN', 'CANCELLED') AND completed_by IS NOT NULL AND completed_at IS NOT NULL)
);
