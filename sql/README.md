# SQL 使用说明

## 结构基线

项目只维护两条可独立重建数据库结构的基线脚本：

- `init/01_sky_take_out.sql`：外卖基础业务结构。脚本包含 `DROP TABLE`，只能用于全新数据库或允许重建数据的环境。
- `init/02_seckill_coupon.sql`：秒杀券流程1-6的最终结构，包含 `seckill_coupon`、`user_coupon`、`seckill_coupon_claim_failure` 和 `seckill_coupon_claim_settlement`。

`test/seckill_coupon_test_data.sql` 只负责本地/JMeter测试数据，不属于结构基线，也不能在生产数据库执行。

## 全新数据库执行顺序

1. 执行 `init/01_sky_take_out.sql`。
2. 执行 `init/02_seckill_coupon.sql`。
3. 通过 `information_schema.tables` 和 `information_schema.columns` 核对15张表及秒杀表字段。
4. 功能测试或压测前，再按需执行 `test/seckill_coupon_test_data.sql`。
5. 执行测试数据后启动或重启服务，让启动初始化器将活动同步到Redis。

## 当前单人开发阶段的临时迁移流程

当前项目尚未发布，也没有共享测试或生产环境。新增字段或表时按以下顺序处理：

1. 在 `sql/migration` 创建一次性临时迁移脚本。
2. 先对当前 `sky_take_out` 数据库执行迁移脚本。
3. 查询 `information_schema`，确认新表、字段、索引已经真实生效；数据库客户端需要刷新表目录。
4. 将迁移后的最终表结构同步回 `init/02_seckill_coupon.sql`。
5. 对比当前数据库与结构基线一致后，删除已经完成使命的临时迁移脚本。

`CREATE TABLE IF NOT EXISTS` 不会给已经存在的旧表补字段，因此已有数据库升级必须先执行临时迁移，不能只修改并重新执行初始化脚本。

## 共享环境边界

一旦项目存在团队共享测试环境、预发布环境或生产环境，就停止删除迁移历史：

- 已发布迁移只能新增，不能删除、改名或回写修改。
- 使用Flyway或Liquibase记录版本和执行状态。
- 初始化基线用于新环境，版本化迁移用于升级已有环境。

## 秒杀表职责

- `seckill_coupon`：秒杀券活动和MySQL剩余库存。
- `user_coupon`：用户领取最终事实，通过 `uk_claim_id` 和 `uk_coupon_user` 保证幂等。
- `seckill_coupon_claim_failure`：流程5最终失败事实，以及流程6A/B的识别、租约、退避、修复和结论。
- `seckill_coupon_claim_settlement`：流程6C活动结束后的MySQL、Redis和失败记录总账快照。

## 注意事项

- 初始化脚本不会由Spring Boot自动执行，需要手动导入。
- Redis预扣库存、已领取用户集合和领取流水属于运行时正确性证据，不是MySQL表。
- 非法、待对账和死信消息先进入 `sky-seckill-coupon-claim-failure` 治理Topic，再由独立消费者异步写入失败表。
- 流程6A扫描 `RECONCILE_PENDING/DEAD_LETTERED/RECHECK_PENDING`；流程6B只修复强证据完整的 `REPAIR_PENDING`；流程6C核对活动级总账。
- `MANUAL_REQUIRED` 只写数据库、结构化日志和告警，不向商家管理端暴露治理详情。
- JMeter并发领取需要准备不同用户身份，同一用户会受到一人一券唯一约束保护。
