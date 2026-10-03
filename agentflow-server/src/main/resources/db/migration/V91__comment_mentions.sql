-- 旧评论没有提醒对象；不根据当前参与者补造历史，也不改变审批状态或版本。
ALTER TABLE application_comment ADD COLUMN mentions_json TEXT NOT NULL DEFAULT '[]';
