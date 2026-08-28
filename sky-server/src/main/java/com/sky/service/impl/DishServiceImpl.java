package com.sky.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.alibaba.fastjson2.TypeReference;
import com.sky.cache.DisplayCacheKeys;
import com.sky.cache.DisplayCacheService;
import com.sky.cache.event.DisplayCacheInvalidationEvent;
import com.sky.constant.MessageConstant;
import com.sky.constant.StatusConstant;
import com.sky.dto.DishDTO;
import com.sky.dto.DishPageQueryDTO;
import com.sky.entity.Dish;
import com.sky.entity.DishFlavor;
import com.sky.exception.DeletionNotAllowedException;
import com.sky.mapper.DishFlavorMapper;
import com.sky.mapper.DishMapper;
import com.sky.result.PageResult;
import com.sky.service.DishService;
import com.sky.vo.DishVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@Slf4j
public class DishServiceImpl implements DishService {

    private static final Type DISH_LIST_TYPE =
            new TypeReference<List<DishVO>>() { }.getType();

    @Autowired
    private DishMapper dishMapper;

    @Autowired
    private DishFlavorMapper dishFlavorMapper;

    @Autowired
    private DisplayCacheService displayCacheService;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    /**
     * 条件查询菜品和口味
     * @param dish
     * @return
     */
    @Override
    public List<DishVO> listWithFlavor(Dish dish) {
        List<Dish> dishList = dishMapper.list(dish);
        List<DishVO> dishVOList = new ArrayList<>();

        for (Dish d : dishList) {
            DishVO dishVO = new DishVO();
            BeanUtils.copyProperties(d, dishVO);

            List<DishFlavor> flavors = dishFlavorMapper.getByDishId(d.getId());
            dishVO.setFlavors(flavors);
            dishVOList.add(dishVO);
        }

        return dishVOList;
    }

    /**
     * 用户查询链：Caffeine L1 -> 普通Redis L2 -> MySQL。
     */
    @Override
    public List<DishVO> listAvailableByCategoryId(Long categoryId) {
        String key = DisplayCacheKeys.dishList(categoryId);
        return displayCacheService.query(key, DISH_LIST_TYPE, () -> {
            Dish dish = new Dish();
            dish.setCategoryId(categoryId);
            dish.setStatus(StatusConstant.ENABLE);
            return listWithFlavor(dish);
        });
    }

    /**
     * 新增菜品
     * @param dishDTO
     */
    @Override
    @Transactional
    public void saveWithFlavor(DishDTO dishDTO) {
        Dish dish = new Dish();
        BeanUtils.copyProperties(dishDTO, dish);

        //插入一条菜品
        dishMapper.insert(dish);

        //插入多条喜好
        Long dishId = dish.getId();

        List<DishFlavor> flavors = dishDTO.getFlavors();
        if (flavors != null && flavors.size() > 0) {
            flavors.forEach(dishFlavor -> dishFlavor.setDishId(dishId));
            dishFlavorMapper.insertBatch(flavors);
        }
        // 事务内只登记Key；真正删除发生在MySQL提交成功之后。
        publishCacheInvalidation(List.of(dishDTO.getCategoryId()));
    }

    /**
     * 菜品分页查询
     * @param dishPageQueryDTO
     * @return
     */
    @Override
    public PageResult pageQuery(DishPageQueryDTO dishPageQueryDTO) {
        PageHelper.startPage(dishPageQueryDTO.getPage(),dishPageQueryDTO.getPageSize());
        Page<DishVO> page = dishMapper.pageQuery(dishPageQueryDTO);
        return new PageResult(page.getTotal(), page.getResult());
    }


    /**
     * 批量删除菜品
     */
    @Transactional
    @Override
    public void deleteBatch(List<Long> ids) {

        Set<Long> affectedCategoryIds = new LinkedHashSet<>();

        //判断当前是否能删除菜品---是否启用
        for (Long id : ids) {
            Dish dish = dishMapper.getById(id);
            affectedCategoryIds.add(dish.getCategoryId());
            if (dish.getStatus() == StatusConstant.ENABLE) {
                throw new DeletionNotAllowedException("菜品起售中,不能删除");
            }
        }
        //删除菜品前先查 setmeal_dish 关联表，避免删除已经被套餐使用的菜品
        List<Long> setmealIdsByDishIds = dishMapper.getSetmealIdsByDishIds(ids);
        if (setmealIdsByDishIds != null && setmealIdsByDishIds.size() > 0) {
            throw new DeletionNotAllowedException(MessageConstant.DISH_BE_RELATED_BY_SETMEAL);
        }
        //删除菜品和菜品口味
        dishMapper.deleteByIds(ids);
        dishFlavorMapper.deleteByDishIds(ids);

        publishCacheInvalidation(affectedCategoryIds);
    }

    /**
     * 启用停用菜品
     * @param status
     * @param id
     */
    @Override
    @Transactional
    public void startOrStop(Integer status, Long id) {
        Dish original = dishMapper.getById(id);
        Dish dish = Dish.builder()
                .id(id)
                .status(status)
                .build();
        dishMapper.update(dish);

        publishCacheInvalidation(List.of(original.getCategoryId()));
    }

    /**
     * 根据id查询菜品以及口味
     * @param id 菜品id
     * @return 菜品以及口味信息
     */
    @Override
    public DishVO getByIdWithFlavor(Long id) {
        Dish dish = dishMapper.getById(id);
        DishVO dishVO = new DishVO();
        BeanUtils.copyProperties(dish, dishVO);

        List<DishFlavor> flavors = dishFlavorMapper.getByDishId(id);
        dishVO.setFlavors(flavors);
        return dishVO;
    }

    /**
     * 修改菜品以及口味
     * @param dishDTO 菜品修改参数
     */
    @Override
    @Transactional
    public void updateWithFlavor(DishDTO dishDTO) {
        Dish original = dishMapper.getById(dishDTO.getId());
        Dish dish = new Dish();
        BeanUtils.copyProperties(dishDTO, dish);
        dishMapper.update(dish);

        Long dishId = dishDTO.getId();
        dishFlavorMapper.deleteByDishId(dishId);

        List<DishFlavor> flavors = dishDTO.getFlavors();
        if (flavors != null && flavors.size() > 0) {
            flavors.forEach(dishFlavor -> dishFlavor.setDishId(dishId));
            dishFlavorMapper.insertBatch(flavors);
        }

        publishCacheInvalidation(List.of(original.getCategoryId(), dishDTO.getCategoryId()));
    }


    /**
     * 根据分类id查询菜品
     * @return
     */
    @Override
    public List<DishVO> getDishsByCategoryId(Long categoryId) {
        return dishMapper.getDishsByCategoryId(categoryId);
    }

    /**
     * 把受影响分类转换成精确Key，并交给AFTER_COMMIT监听器处理。
     */
    private void publishCacheInvalidation(Collection<Long> categoryIds) {
        Set<String> keys = new LinkedHashSet<>();
        for (Long categoryId : categoryIds) {
            if (categoryId != null) {
                keys.add(DisplayCacheKeys.dishList(categoryId));
            }
        }
        if (!keys.isEmpty()) {
            eventPublisher.publishEvent(new DisplayCacheInvalidationEvent(keys));
        }
    }
}
