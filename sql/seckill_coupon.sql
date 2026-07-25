-- 秒杀券模块建表脚本。
-- 当前脚本只创建新表，不修改现有 orders 表。

CREATE TABLE IF NOT EXISTS `seckill_coupon` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(64) NOT NULL COMMENT '券名称',
  `threshold_amount` decimal(10,2) NOT NULL DEFAULT '0.00' COMMENT '使用门槛金额',
  `discount_amount` decimal(10,2) NOT NULL COMMENT '优惠金额',
  `total_stock` int NOT NULL COMMENT '总库存',
  `remaining_stock` int NOT NULL COMMENT '剩余库存',
  `claim_start_time` datetime NOT NULL COMMENT '开始领取时间',
  `claim_end_time` datetime NOT NULL COMMENT '结束领取时间',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '状态：0停用，1启用',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `create_user` bigint DEFAULT NULL COMMENT '创建人',
  `update_user` bigint DEFAULT NULL COMMENT '修改人',
  PRIMARY KEY (`id`),
  KEY `idx_status_claim_time` (`status`, `claim_start_time`, `claim_end_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀券活动表';

CREATE TABLE IF NOT EXISTS `user_coupon` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `coupon_id` bigint NOT NULL COMMENT '秒杀券id',
  `user_id` bigint NOT NULL COMMENT '用户id',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '状态：0未使用，1已使用，2已过期',
  `claim_time` datetime NOT NULL COMMENT '领取时间',
  `expire_time` datetime DEFAULT NULL COMMENT '过期时间',
  `use_time` datetime DEFAULT NULL COMMENT '使用时间',
  `order_id` bigint DEFAULT NULL COMMENT '使用该券的外卖订单id',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_coupon_user` (`coupon_id`, `user_id`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_order_id` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户秒杀券表';
