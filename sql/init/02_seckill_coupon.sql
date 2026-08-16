-- 秒杀券模块流程1-6的最终结构基线。
-- 当前脚本只创建秒杀业务表，不修改原有外卖业务表。
-- 全新环境直接执行本脚本；已有环境的字段升级必须先执行临时迁移，再将最终结构回写到本脚本。

CREATE DATABASE IF NOT EXISTS `sky_take_out`;
USE `sky_take_out`;

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
  `claim_id` varchar(36) NOT NULL COMMENT '领取流水ID',
  `coupon_id` bigint NOT NULL COMMENT '秒杀券id',
  `user_id` bigint NOT NULL COMMENT '用户id',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '状态：0未使用，1已使用，2已过期',
  `claim_time` datetime NOT NULL COMMENT '领取时间',
  `expire_time` datetime DEFAULT NULL COMMENT '过期时间',
  `use_time` datetime DEFAULT NULL COMMENT '使用时间',
  `order_id` bigint DEFAULT NULL COMMENT '使用该券的外卖订单id',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_claim_id` (`claim_id`),
  UNIQUE KEY `uk_coupon_user` (`coupon_id`, `user_id`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_order_id` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户秒杀券表';

CREATE TABLE IF NOT EXISTS `seckill_coupon_claim_failure` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `failure_id` varchar(36) NOT NULL COMMENT '幂等失败事件ID',
  `source_message_id` varchar(128) DEFAULT NULL COMMENT '原始RocketMQ消息ID',
  `source_topic` varchar(255) DEFAULT NULL COMMENT '原始RocketMQ Topic',
  `claim_id` varchar(36) DEFAULT NULL COMMENT '领取流水ID',
  `coupon_id` bigint DEFAULT NULL COMMENT '秒杀券ID',
  `user_id` bigint DEFAULT NULL COMMENT '用户ID',
  `action` varchar(24) NOT NULL COMMENT '治理动作：QUARANTINE/RECONCILE/DEAD_LETTER',
  `status` varchar(32) NOT NULL COMMENT '处理状态：待处理/处理中/待修复/人工处理/已解决',
  `error_code` varchar(64) NOT NULL COMMENT '稳定错误码',
  `error_message` varchar(1000) DEFAULT NULL COMMENT '失败摘要',
  `message_body` text COMMENT '原始或序列化消息体',
  `delivery_attempts` int NOT NULL DEFAULT '1' COMMENT '原消息投递次数',
  `reconcile_attempts` int NOT NULL DEFAULT '0' COMMENT '流程6A对账尝试次数',
  `repair_attempts` int NOT NULL DEFAULT '0' COMMENT '流程6B受控修复尝试次数',
  `next_reconcile_time` datetime DEFAULT NULL COMMENT '退避后的下次执行时间',
  `processing_deadline` datetime DEFAULT NULL COMMENT '当前处理租约截止时间',
  `processing_token` varchar(36) DEFAULT NULL COMMENT '当前处理租约所有权令牌',
  `resolution_code` varchar(64) DEFAULT NULL COMMENT '稳定治理结论码',
  `resolution_message` varchar(1000) DEFAULT NULL COMMENT '内部治理结论摘要',
  `occurred_time` datetime NOT NULL COMMENT '失败治理事件发生时间',
  `resolved_time` datetime DEFAULT NULL COMMENT '恢复成功时间',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_time` datetime NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_failure_id` (`failure_id`),
  KEY `idx_source_message_id` (`source_message_id`),
  KEY `idx_claim_id` (`claim_id`),
  KEY `idx_status_update_time` (`status`, `update_time`),
  KEY `idx_coupon_user` (`coupon_id`, `user_id`),
  KEY `idx_status_next_reconcile` (`status`, `next_reconcile_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀券领取最终失败治理表';

CREATE TABLE IF NOT EXISTS `seckill_coupon_claim_settlement` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `coupon_id` bigint NOT NULL COMMENT '秒杀券活动ID',
  `status` varchar(32) NOT NULL COMMENT 'PENDING/PROCESSING/RECHECK_PENDING/CONSISTENT/MANUAL_REQUIRED',
  `reconcile_attempts` int NOT NULL DEFAULT '0' COMMENT '活动总账核对次数',
  `next_reconcile_time` datetime DEFAULT NULL COMMENT '退避后的下次核对时间',
  `processing_deadline` datetime DEFAULT NULL COMMENT '当前结算租约截止时间',
  `processing_token` varchar(36) DEFAULT NULL COMMENT '当前结算租约所有权令牌',
  `total_stock` int DEFAULT NULL COMMENT '核对时活动总库存',
  `mysql_remaining_stock` int DEFAULT NULL COMMENT '核对时MySQL剩余库存',
  `mysql_claim_count` bigint DEFAULT NULL COMMENT 'MySQL用户券记录数',
  `redis_remaining_stock` bigint DEFAULT NULL COMMENT 'Redis活动剩余库存',
  `redis_user_count` bigint DEFAULT NULL COMMENT 'Redis已领取用户数',
  `redis_claim_count` bigint DEFAULT NULL COMMENT 'Redis领取流水数',
  `unresolved_failure_count` bigint DEFAULT NULL COMMENT '该券未解决治理记录数',
  `resolution_code` varchar(64) DEFAULT NULL COMMENT '稳定结算结论码',
  `resolution_message` varchar(1000) DEFAULT NULL COMMENT '本次总账摘要',
  `last_checked_time` datetime DEFAULT NULL COMMENT '最近核对时间',
  `settled_time` datetime DEFAULT NULL COMMENT '总账一致时间',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_time` datetime NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_coupon_id` (`coupon_id`),
  KEY `idx_status_next_reconcile` (`status`, `next_reconcile_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀券活动领取总账结算表';
