package com.sky.task;


import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@Slf4j


public class OrderTask {
    @Autowired
    private OrderMapper orderMapper;

    /**
     *处理超时订单
     */
    @Scheduled(cron = "0 * * * * ? ")
    public void porcessTimeOutOrder(){
        log.info("定时处理超时订单:{}", LocalDateTime.now());
        LocalDateTime time = LocalDateTime.now().plusMinutes(-15);
        //select * from order where status = ? and ordertime < {now - 15min}
        List<Orders> byStatusAndOrderTimeLT =
                orderMapper.getByStatusAndOrderTimeLT(Orders.PENDING_PAYMENT, time);
        //遍历处理
        if(byStatusAndOrderTimeLT != null && byStatusAndOrderTimeLT.size()>0){
            for(Orders orders:byStatusAndOrderTimeLT){
                orders.setStatus(Orders.CANCELLED);
                orders.setCancelReason("订单超时,自动取消");
                orders.setCancelTime(LocalDateTime.now());
                orderMapper.update(orders);
            }
        }
    }


    /**
     * 处理一直处于派送中订单,每天凌晨1点处理前一天的所有订单，time=now-60min
     */
    @Scheduled(cron = "0 0 1 * * ?")
    public void processDeliveryOrder(){
        log.info("定时处理派送中订单:{}", LocalDateTime.now());
        LocalDateTime time = LocalDateTime.now().plusMinutes(-60);
        List<Orders> byStatusAndOrderTimeLT =
                orderMapper.getByStatusAndOrderTimeLT(Orders.DELIVERY_IN_PROGRESS, time);
        if(byStatusAndOrderTimeLT != null && byStatusAndOrderTimeLT.size()>0){
            for(Orders orders:byStatusAndOrderTimeLT){
                orders.setStatus(Orders.COMPLETED);
                orders.setDeliveryTime(LocalDateTime.now());
                orderMapper.update(orders);
            }
        }
    }
}
