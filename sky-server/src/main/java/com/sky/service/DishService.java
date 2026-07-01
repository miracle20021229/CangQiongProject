package com.sky.service;

import com.sky.dto.DishDTO;
import com.sky.dto.DishPageQueryDTO;
import com.sky.result.PageResult;
import com.sky.vo.DishVO;

import java.util.List;

public interface DishService {
    /**
     * 新增菜品与对应的喜好
     * @param dishDTO
     * @return
     */
    public void saveWithFlavor(DishDTO dishDTO);

    /**
     * 菜品分页查询
     * @param dishPageQueryDTO
     * @return
     */
    PageResult pageQuery(DishPageQueryDTO dishPageQueryDTO);

    /**
     * 批量删除菜品
     */
    void deleteBatch(List<Long> ids);

    void startOrStop(Integer status, Long id);

    /**
     * 根据id查询菜品以及口味
     * @param id 菜品id
     * @return 菜品以及口味信息
     */
    DishVO getByIdWithFlavor(Long id);

    /**
     * 修改菜品以及口味
     * @param dishDTO 菜品修改参数
     */
    void updateWithFlavor(DishDTO dishDTO);
}
