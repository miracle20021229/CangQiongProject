package com.sky.service;

import com.sky.dto.SetmealDTO;
import com.sky.dto.SetmealPageQueryDTO;
import com.sky.entity.Setmeal;
import com.sky.result.PageResult;
import com.sky.vo.DishItemVO;
import com.sky.vo.SetmealVO;
import org.springframework.stereotype.Service;

import java.util.List;


public interface SetmealService {
    /**
     * 新增套餐
     * @param setmealDTO
     */
    void save(SetmealDTO setmealDTO);

    /**
     * 根据id查询套餐
     * @param id
     * @return
     */
    SetmealVO getById(Long id);

    /**
     * 套餐分页查询
     * @param setmealPageQueryDTO
     * @return
     */
    PageResult setmealPagequery(SetmealPageQueryDTO setmealPageQueryDTO);

    /**
     * 根据id启用停用套餐
     * @param status
     * @param id
     */
    void startOrStopSetmeal(Integer status, Long id);

    /**
     * 批量删除套餐
     * @param ids
     */
    void deleta(List<Long> ids);

    /**
     * 修改套餐
     * @param setmealDTO
     */
    void updata(SetmealDTO setmealDTO);

    /**
     * 条件查询套餐
     * @param setmeal
     * @return
     */
    List<Setmeal> list(Setmeal setmeal);

    /**
     * 根据id查询套餐中的菜品选项
     * @param id
     * @return
     */
    List<DishItemVO> getDishItemById(Long id);
}
