-- 仅用于已经创建过user_coupon表的数据库，执行一次后再启动流程3、4代码。
ALTER TABLE `user_coupon`
  ADD COLUMN `claim_id` varchar(36) NULL COMMENT '领取流水ID' AFTER `id`;

UPDATE `user_coupon`
SET `claim_id` = concat('legacy-', `id`)
WHERE `claim_id` IS NULL;

ALTER TABLE `user_coupon`
  MODIFY COLUMN `claim_id` varchar(36) NOT NULL COMMENT '领取流水ID',
  ADD UNIQUE KEY `uk_claim_id` (`claim_id`);
