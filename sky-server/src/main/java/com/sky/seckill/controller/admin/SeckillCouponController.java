package com.sky.seckill.controller.admin;

import com.sky.dto.SeckillCouponDTO;
import com.sky.dto.SeckillCouponPageQueryDTO;
import com.sky.result.PageResult;
import com.sky.result.Result;
import com.sky.seckill.service.SeckillCouponAdminService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端秒杀券接口。
 */
@RestController("adminSeckillCouponController")
@RequestMapping("/admin/seckillCoupon")
@Api(tags = "秒杀券管理接口")
@Slf4j
public class SeckillCouponController {

    // 编排管理端秒杀券增删改查用例的应用服务。
    private final SeckillCouponAdminService seckillCouponAdminService;

    /**
     * 创建管理端秒杀券接口并注入应用服务。
     *
     * @param seckillCouponAdminService 管理端秒杀券应用服务
     */
    public SeckillCouponController(SeckillCouponAdminService seckillCouponAdminService) {
        this.seckillCouponAdminService = seckillCouponAdminService;
    }

    /**
     * 新增秒杀券。
     */
    @PostMapping
    @ApiOperation("新增秒杀券")
    public Result<String> save(@RequestBody SeckillCouponDTO seckillCouponDTO) {
        log.info("新增秒杀券：{}", seckillCouponDTO);
        seckillCouponAdminService.save(seckillCouponDTO);
        return Result.success();
    }

    /**
     * 分页查询秒杀券。
     */
    @GetMapping("/page")
    @ApiOperation("秒杀券分页查询")
    public Result<PageResult> page(SeckillCouponPageQueryDTO queryDTO) {
        return Result.success(seckillCouponAdminService.pageQuery(queryDTO));
    }

    /**
     * 修改秒杀券。
     */
    @PutMapping
    @ApiOperation("修改秒杀券")
    public Result<String> update(@RequestBody SeckillCouponDTO seckillCouponDTO) {
        log.info("修改秒杀券：{}", seckillCouponDTO);
        seckillCouponAdminService.update(seckillCouponDTO);
        return Result.success();
    }

    /**
     * 启用或停用秒杀券。
     */
    @PostMapping("/status/{status}")
    @ApiOperation("启用或停用秒杀券")
    public Result<String> startOrStop(@PathVariable Integer status, @RequestParam Long id) {
        log.info("修改秒杀券状态：status={}, id={}", status, id);
        seckillCouponAdminService.startOrStop(status, id);
        return Result.success();
    }
}
