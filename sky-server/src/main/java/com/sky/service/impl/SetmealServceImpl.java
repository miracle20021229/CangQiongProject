package com.sky.service.impl;

import com.alibaba.fastjson2.TypeReference;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.cache.DisplayCacheKeys;
import com.sky.cache.DisplayCacheService;
import com.sky.cache.event.DisplayCacheInvalidationEvent;
import com.sky.constant.MessageConstant;
import com.sky.constant.StatusConstant;
import com.sky.context.BaseContext;
import com.sky.dto.SetmealDTO;
import com.sky.dto.SetmealPageQueryDTO;
import com.sky.entity.Setmeal;
import com.sky.entity.SetmealDish;
import com.sky.exception.BaseException;
import com.sky.mapper.SetmealDishMapper;
import com.sky.mapper.SetmealMapper;
import com.sky.result.PageResult;
import com.sky.service.SetmealService;
import com.sky.vo.DishItemVO;
import com.sky.vo.SetmealVO;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Type;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service

public class SetmealServceImpl implements SetmealService {

    private static final Type SETMEAL_LIST_TYPE =
            new TypeReference<List<Setmeal>>() { }.getType();

    @Autowired
    private SetmealMapper setmealMapper;

    @Autowired
    private SetmealDishMapper setmealDishMapper;

    @Autowired
    private DisplayCacheService displayCacheService;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    /**
     * 新增套餐
     * @param setmealDTO
     */
    @Override
    @Transactional
    public void save(SetmealDTO setmealDTO) {
        //判断套餐名称是否已经存在
        Integer count = setmealMapper.countByName(setmealDTO.getName());
        if (count > 0) {
            throw new BaseException(MessageConstant.SETMEAL_NAME_ALREADY_EXISTS);
        }

        //创建一个新的setmeal对象拷贝属性并
        Setmeal setmeal = new Setmeal();
        BeanUtils.copyProperties(setmealDTO, setmeal);
        //补全剩下的属性
        LocalDateTime now = LocalDateTime.now();
        setmeal.setCreateTime(now);
        setmeal.setUpdateTime(now);
        setmeal.setCreateUser(BaseContext.getCurrentId());
        setmeal.setUpdateUser(BaseContext.getCurrentId());
        //新增一个套餐
        setmealMapper.insert(setmeal);
        //新建一个setmealdish对象
        Long setmealId = setmeal.getId();
        //新增一个 套餐所含的所有菜品list
        List<SetmealDish> setmealDishes = setmealDTO.getSetmealDishes();
        if (setmealDishes != null && setmealDishes.size() > 0) {
            setmealDishes.forEach(setmealDish -> setmealDish.setSetmealId(setmealId));
            setmealMapper.insertWithDish(setmealDishes);
        }
        publishCacheInvalidation(List.of(setmealDTO.getCategoryId()));
    }

    /**
     * 根据id查询套餐
     * @param id
     * @return
     */
    @Override
    public SetmealVO getById(Long id) {
        //查询stemeal表,返回setmeal对象
        Setmeal setmeal = setmealMapper.getById(id);
        if (setmeal == null) {
            throw new BaseException("套餐不存在");
        }
        //拷贝到setmealVO中
        SetmealVO setmealVO = new SetmealVO();
        BeanUtils.copyProperties(setmeal, setmealVO);
        //查询setmeal_dish表返回dishs封装到setmealVO中
        List<SetmealDish> setmealDishes = setmealDishMapper.getDishsBySetmealId(id);
        setmealVO.setSetmealDishes(setmealDishes);
        return setmealVO;
    }

    /**
     * 套餐分页查询
     * @param setmealPageQueryDTO
     * @return
     */
    @Override
    public PageResult setmealPagequery(SetmealPageQueryDTO setmealPageQueryDTO) {
        //分页查询套餐(setmealDishs = null)
        PageHelper.startPage(setmealPageQueryDTO.getPage(),setmealPageQueryDTO.getPageSize());
        Page<SetmealVO> page = setmealMapper.pageQuery(setmealPageQueryDTO);
        //为stemealDishs赋值(通过stemealDishMapper查询)
        page.getResult().forEach(setmealVO -> {
            List<SetmealDish> setmealDishes = setmealDishMapper.getDishsBySetmealId(setmealVO.getId());
            setmealVO.setSetmealDishes(setmealDishes);
        });

        return new PageResult(page.getTotal(), page.getResult());
    }


    /**
     * 根据id启用停用套餐
     * @param status
     * @param id
     */
    @Override
    @Transactional
    public void startOrStopSetmeal(Integer status, Long id) {
        Setmeal original = setmealMapper.getById(id);
        Setmeal setmeal = Setmeal.builder().status(status).id(id).build();
        setmealMapper.updata(setmeal);
        publishCacheInvalidation(List.of(original.getCategoryId()));
    }

    /**
     * 批量删除套餐
     * @param ids
     */
    @Override
    @Transactional
    public void deleta(List<Long> ids) {
        Set<Long> affectedCategoryIds = new LinkedHashSet<>();
        for (Long id : ids) {
            Setmeal setmeal = setmealMapper.getById(id);
            if (setmeal != null) {
                affectedCategoryIds.add(setmeal.getCategoryId());
            }
        }
        setmealMapper.delete(ids);
        publishCacheInvalidation(affectedCategoryIds);
    }


    /**
     * 修改套餐
     * @param setmealDTO
     */
    @Override
    @Transactional
    public void updata(SetmealDTO setmealDTO) {
        Setmeal original = setmealMapper.getById(setmealDTO.getId());
        Setmeal setmeal = Setmeal.builder()
                .id(setmealDTO.getId())
                .categoryId(setmealDTO.getCategoryId())
                .name(setmealDTO.getName())
                .price(setmealDTO.getPrice())
                .status(setmealDTO.getStatus())
                .description(setmealDTO.getDescription())
                .image(setmealDTO.getImage())
                .build();

        setmealMapper.updata(setmeal);
        publishCacheInvalidation(List.of(original.getCategoryId(), setmealDTO.getCategoryId()));
    }

    /**
     * 条件查询套餐
     * @param setmeal
     * @return
     */
    @Override
    public List<Setmeal> list(Setmeal setmeal) {
        return setmealMapper.list(setmeal);
    }

    /**
     * 用户查询链：Caffeine L1 -> 普通Redis L2 -> MySQL。
     */
    @Override
    public List<Setmeal> listAvailableByCategoryId(Long categoryId) {
        String key = DisplayCacheKeys.setmealList(categoryId);
        return displayCacheService.query(key, SETMEAL_LIST_TYPE, () -> {
            Setmeal setmeal = new Setmeal();
            setmeal.setCategoryId(categoryId);
            setmeal.setStatus(StatusConstant.ENABLE);
            return setmealMapper.list(setmeal);
        });
    }

    /**
     * 根据id查询套餐中的菜品选项
     * @param id
     * @return
     */
    @Override
    public List<DishItemVO> getDishItemById(Long id) {
        return setmealMapper.getDishItemBySetmealId(id);
    }

    /**
     * 把受影响分类转换成精确Key，并交给AFTER_COMMIT监听器处理。
     */
    private void publishCacheInvalidation(Collection<Long> categoryIds) {
        Set<String> keys = new LinkedHashSet<>();
        for (Long categoryId : categoryIds) {
            if (categoryId != null) {
                keys.add(DisplayCacheKeys.setmealList(categoryId));
            }
        }
        if (!keys.isEmpty()) {
            eventPublisher.publishEvent(new DisplayCacheInvalidationEvent(keys));
        }
    }
}
