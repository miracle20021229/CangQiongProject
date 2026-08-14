package com.sky.controller.user;

import com.sky.result.Result;
import com.sky.service.SeckillCouponUserClaimService;
import com.sky.service.SeckillCouponUserQueryService;
import com.sky.vo.SeckillCouponVO;
import com.sky.vo.UserCouponVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
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

    private final SeckillCouponUserQueryService seckillCouponUserQueryService;
    private final SeckillCouponUserClaimService seckillCouponUserClaimService;

    //构造注入
    public SeckillCouponController(SeckillCouponUserQueryService seckillCouponUserQueryService, SeckillCouponUserClaimService seckillCouponUserClaimService) {
        this.seckillCouponUserQueryService = seckillCouponUserQueryService;
        this.seckillCouponUserClaimService = seckillCouponUserClaimService;
    }

    /**
     * 查询当前处于启用状态、领取时间内且仍有库存的秒杀券。
     */
    @GetMapping("/list")
    @ApiOperation("查询可领取的秒杀券")
    public Result<List<SeckillCouponVO>> list() {
        return Result.success(seckillCouponUserQueryService.listAvailable());
    }

    /**
     * 流程3-步骤1：接收领取请求并调用用户领取用例，Controller不处理Redis和MQ细节。
     */
    @PostMapping("/claim/{id}")
    @ApiOperation("领取秒杀券")
    public Result<String> claim(@PathVariable("id") Long couponId) {
        log.info("用户领取秒杀券，秒杀券ID：{}", couponId);
        // 流程3-步骤1：调用领取Service，箭头下一站是SeckillCouponUserClaimService.claim()。
        return Result.success(seckillCouponUserClaimService.claim(couponId));
    }

    /**
     * 查询当前登录用户领取过的全部秒杀券。
     */
    @GetMapping("/mine")
    @ApiOperation("查询我的秒杀券")
    public Result<List<UserCouponVO>> mine() {
        return Result.success(seckillCouponUserQueryService.listMine());
    }
}
