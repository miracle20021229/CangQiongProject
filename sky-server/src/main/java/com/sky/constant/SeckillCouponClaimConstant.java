package com.sky.constant;

/**
 * 秒杀券领取流程常量。
 */
public final class SeckillCouponClaimConstant {

    public static final Long SUCCESS = 0L;
    public static final Long OUT_OF_STOCK = 1L;
    public static final Long DUPLICATE_CLAIM = 2L;
    public static final Long ACTIVITY_NOT_INITIALIZED = 3L;
    public static final Long ACTIVITY_DISABLED = 4L;
    public static final Long ACTIVITY_NOT_STARTED = 5L;
    public static final Long ACTIVITY_ENDED = 6L;
    public static final String CLAIM_TAG = "CLAIM";

    private SeckillCouponClaimConstant() {
    }
}
