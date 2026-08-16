package com.sky.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 用户侧异步领取结果，只暴露业务状态，不包含内部失败治理信息。
 * 无参构造、全参构造及访问方法由Lombok生成，字段职责见逐项属性注释。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeckillCouponClaimStatusVO implements Serializable {

    // 领取消息仍在主链或治理链处理中。
    public static final String PROCESSING = "PROCESSING";
    // 用户券已经在MySQL中落库。
    public static final String SUCCESS = "SUCCESS";
    // 领取已经进入不能继续自动修复的终态。
    public static final String FAILED = "FAILED";

    // Java序列化版本号。
    private static final long serialVersionUID = 1L;

    // 用户提交领取后获得的流水ID。
    private String claimId;
    // 面向用户抽象后的领取状态。
    private String status;
}
