package com.sky.controller.admin;

import com.sky.dto.SeckillCouponDTO;
import com.sky.dto.SeckillCouponPageQueryDTO;
import com.sky.result.PageResult;
import com.sky.result.Result;
import com.sky.service.SeckillCouponService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController("adminSeckillCouponController")
@RequestMapping("/admin/seckillCoupon")
@Api(tags = "秒杀券管理接口")
@Slf4j
public class SeckillCouponController {

    @Autowired
    private SeckillCouponService seckillCouponService;

    @PostMapping
    @ApiOperation("新增秒杀券")
    public Result<String> save(@RequestBody SeckillCouponDTO seckillCouponDTO) {
        log.info("新增秒杀券：{}", seckillCouponDTO);
        seckillCouponService.save(seckillCouponDTO);
        return Result.success();
    }

    @GetMapping("/page")
    @ApiOperation("秒杀券分页查询")
    public Result<PageResult> page(SeckillCouponPageQueryDTO queryDTO) {
        return Result.success(seckillCouponService.pageQuery(queryDTO));
    }

    @PutMapping
    @ApiOperation("修改秒杀券")
    public Result<String> update(@RequestBody SeckillCouponDTO seckillCouponDTO) {
        log.info("修改秒杀券：{}", seckillCouponDTO);
        seckillCouponService.update(seckillCouponDTO);
        return Result.success();
    }

    @PostMapping("/status/{status}")
    @ApiOperation("启用或停用秒杀券")
    public Result<String> startOrStop(@PathVariable Integer status, @RequestParam Long id) {
        log.info("修改秒杀券状态：status={}, id={}", status, id);
        seckillCouponService.startOrStop(status, id);
        return Result.success();
    }
}
