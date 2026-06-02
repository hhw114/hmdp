--传入参数
local voucherId = ARGV[1]

local userId = ARGV[2]

--数据key
--1.库存key
local stockKey = 'seckill:stock:' .. voucherId
--2.订单key
local orderKey = 'seckill:order:' .. voucherId


--判断库存是否充足
if(tonumber(redis.call('get',stockKey)) <= 0) then
    --库存不足
    return 1
end

--判断用户是否已经下过单
if(redis.call('sisnumber',orderKey,userId) == 1) then
    --存在，重复下单
    return 2
end
--扣库存
redis.call('incrby',stockKey,-1)
--保存用户
redis.call('sadd',orderKey,userId)