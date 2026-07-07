package com.sky.controller.user;

import com.sky.constant.StatusConstant;
import com.sky.entity.Dish;
import com.sky.result.Result;
import com.sky.service.DishService;
import com.sky.vo.DishVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController("userDishController")
@RequestMapping("/user/dish")
@Slf4j
@Api(tags = "C端-菜品浏览接口")
public class DishController {
    @Autowired
    private DishService dishService;
    @Autowired
    RedisTemplate redisTemplate;
    /**
     * 根据分类id查询菜品
     * @param categoryId
     * @return
     */
    @GetMapping("/list")
    @ApiOperation("根据分类id查询菜品")
    public Result<List<DishVO>> list(Long categoryId) {
        //根据"dish_" + categoryId规则构造key
        String key = "dish_" + categoryId;
        //在redis中根据分类id查询菜品
        List<DishVO> list = (List<DishVO>) redisTemplate.opsForValue().get(key);
        //如果存在就返回
        if (list != null && list.size()>0) {
            log.info("根据分类id查询redis:{}",categoryId);
            return Result.success(list);
        }
        //如果不存在，查询数据库并写入缓存
        else{
            log.info("根据分类id查询mysql:{}",categoryId);
            //新建一个dish对象传入查询
            Dish dish = new Dish();
            dish.setCategoryId(categoryId);
            dish.setStatus(StatusConstant.ENABLE);
            //查询起售中的菜品
            list = dishService.listWithFlavor(dish);
            //写入缓存
            redisTemplate.opsForValue().set(key,list);
            return Result.success(list);
        }
    }

}
