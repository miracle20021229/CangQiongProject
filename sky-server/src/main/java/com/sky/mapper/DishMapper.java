package com.sky.mapper;

import com.github.pagehelper.Page;
import com.sky.annotation.AutoFill;
import com.sky.dto.DishPageQueryDTO;
import com.sky.entity.Dish;
import com.sky.enumeration.OperationType;
import com.sky.vo.DishVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DishMapper {

    /**
     * 根据分类id查询菜品数量
     * @param categoryId
     * @return
     */
    @Select("select count(id) from dish where category_id = #{categoryId}")
    Integer countByCategoryId(Long categoryId);

    /**
     * 新增菜品
     * @param dish
     */
    @AutoFill(value = OperationType.INSERT)
    void insert(Dish dish);

    /**
     * 菜品分页查询
     * @param dishPageQueryDTO
     * @return
     */
    Page<DishVO> pageQuery(DishPageQueryDTO dishPageQueryDTO);

    /**
     * 根据主键查询菜品(批量删除菜品)
     * @param id
     * @return
     */
    @Select("select * from dish where id = #{id}")
    Dish getById(Long id);

    @AutoFill(value = OperationType.UPDATE)
    void update(Dish dish);

    void deleteByIds(@Param("ids") List<Long> ids);

    /**
     * 根据菜品id列表查询关联的套餐id
     * 用于批量删除菜品前校验：如果返回结果不为空，说明这些菜品被套餐使用，不能删除。
     * @param ids 菜品id列表
     * @return 关联的套餐id列表
     */
    List<Long> getSetmealIdsByDishIds(@Param("ids") List<Long> ids);

    /**
     * 动态条件查询菜品
     * @param dish
     * @return
     */
    List<Dish> list(Dish dish);

    /**
     * 根据分类id查询菜品
     * @return
     */
    List<DishVO> getDishsByCategoryId(Long categoryId);
}
