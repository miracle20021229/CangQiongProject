-- 流程3-步骤10：由SeckillCouponRedisRepository.tryPreDeduct()调用，在Redis内原子完成领取判定和预扣。
-- KEYS[1] 秒杀券活动Hash：status、startTime、endTime、stock、cleanupTime
-- KEYS[2] 已领取用户集合键
-- KEYS[3] 领取流水Hash：claimId -> userId，供RocketMQ事务回查
-- ARGV[1] 当前用户 id
-- ARGV[2] 领取流水 id
-- 返回值：0成功，1库存不足，2重复领取，3活动未初始化，4活动停用，5活动未开始，6活动已结束

-- Redis 3.2在脚本执行TIME等非确定性命令后，需要改为逐条复制写命令，否则后续写操作会被拒绝。
redis.replicate_commands()

local activity = redis.call('HMGET', KEYS[1], 'status', 'startTime', 'endTime', 'stock', 'cleanupTime')
local status = activity[1]
local startTime = tonumber(activity[2])
local endTime = tonumber(activity[3])
local stock = tonumber(activity[4])
local cleanupTime = tonumber(activity[5])

if not status or not startTime or not endTime or not stock then
    return 3
end

if status ~= '1' then
    return 4
end

local now = tonumber(redis.call('TIME')[1])
if now < startTime then
    return 5
end

if now >= endTime then
    return 6
end

if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return 2
end

if stock <= 0 then
    return 1
end

redis.call('HINCRBY', KEYS[1], 'stock', -1)
-- users集合由首次SADD创建，并在同一脚本内设置清理时间
redis.call('SADD', KEYS[2], ARGV[1])
redis.call('HSET', KEYS[3], ARGV[2], ARGV[1])
if cleanupTime then
    redis.call('EXPIREAT', KEYS[2], cleanupTime)
    redis.call('EXPIREAT', KEYS[3], cleanupTime)
end
return 0
