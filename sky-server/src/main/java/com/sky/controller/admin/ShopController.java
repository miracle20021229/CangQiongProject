package com.sky.controller.admin;

import com.sky.result.Result;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

@RestController("adminShopController")
@RequestMapping("/admin/shop")
@Api(tags = "商店营业状态接口")
@Slf4j
public class ShopController {

    @Autowired
    private RedisTemplate redisTemplate;

    /**
     * 设置商店营业状态
     * @param status
     * @return
     */
    @PutMapping("/{status}")
    @ApiOperation("设置商店营业状态")
    public Result setShopStatus(@PathVariable Integer status) {
        log.info("设置商店营业状态:{}", status);
        redisTemplate.opsForValue().set("status", status);
        return  Result.success();
    }


    /**
     * 查询商店营业状态
     * @return
     */
    @GetMapping("/status")
    @ApiOperation("查询商店营业状态")
    public Result<Integer> getShopStatus(){
        Integer status = (Integer) redisTemplate.opsForValue().get("status");
        status = status == null ? 1 : status;
        log.info("商店营业状态为:{}",status == 1?"营业":"打烊");
        return  Result.success(status);
    }

}
