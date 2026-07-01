package com.sky.mapper;


import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SetmealDishMapper {
    /**
     * 根据菜品id查询对应的套餐
     * @param ids
     * @return
     */
    List<Long> getSetmealIdsByDishIds(@Param("ids") List<Long> ids);

    /**
     * 根据主键删除菜品
     * @param id
     */
    @Delete("delete from dish where id = #{id}")
    void deleteById(Long id);

    /**
     * 根据主键删除口味
     * @param dishid
     */
    @Delete("delete from dish_flavor where dish_id = #{dishid}")
    void deleteFlavorById(Long dishid);
}
