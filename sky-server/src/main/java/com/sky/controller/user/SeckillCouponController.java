package com.sky.controller.user;

import com.sky.result.Result;
import com.sky.service.SeckillCouponService;
import com.sky.vo.SeckillCouponVO;
import com.sky.vo.UserCouponVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户端秒杀券接口。
 *
 * 提供可领取活动查询、领取秒杀券和查询当前用户券包的能力。
 */
@RestController("userSeckillCouponController")
@RequestMapping("/user/seckillCoupon")
@Api(tags = "用户秒杀券接口")
@Slf4j
public class SeckillCouponController {

    /**
     * 秒杀券业务服务。
     */
    @Autowired
    private SeckillCouponService seckillCouponService;

    /**
     * 查询当前处于启用状态、领取时间内且仍有库存的秒杀券。
     *
     * @return 可领取的秒杀券列表
     */
    @GetMapping("/list")
    @ApiOperation("查询可领取的秒杀券")
    public Result<List<SeckillCouponVO>> list() {
        return Result.success(seckillCouponService.listAvailable());
    }

    /**
     * 当前登录用户领取指定秒杀券。
     *
     * @param couponId 秒杀券ID
     * @return 新增的用户领券记录ID
     */
    @PostMapping("/claim/{id}")
    @ApiOperation("领取秒杀券")
    public Result<Long> claim(@PathVariable("id") Long couponId) {
        log.info("用户领取秒杀券，秒杀券ID：{}", couponId);
        return Result.success(seckillCouponService.claim(couponId));
    }

    /**
     * 查询当前登录用户领取过的全部秒杀券。
     *
     * @return 当前用户的秒杀券列表
     */
    @GetMapping("/mine")
    @ApiOperation("查询我的秒杀券")
    public Result<List<UserCouponVO>> mine() {
        return Result.success(seckillCouponService.listMine());
    }
}
