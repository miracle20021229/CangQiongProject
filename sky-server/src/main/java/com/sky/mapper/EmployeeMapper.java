package com.sky.mapper;

import com.sky.entity.Employee;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface EmployeeMapper {

    /**
     * 根据用户名查询员工
     * @param username 啊啊啊
     * @return 啊啊啊
     */
    Employee getByUsername(String username);

    /*
    * 插入员工数据
    * */
    @Insert("insert into employee " +
            "(name, username, password, phone, sex, id_number, " +
            "status, create_time, update_time, create_user, update_user) " +
            "values " +
            "(#{name}, #{username}, #{password}, #{phone}, #{sex}, #{idNumber}, " +
            "#{status}, #{createTime}, #{updateTime}, #{createUser}, #{updateUser})")
    void insert(Employee employee);
}
