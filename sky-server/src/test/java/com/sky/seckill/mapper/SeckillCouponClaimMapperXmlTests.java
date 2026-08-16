package com.sky.seckill.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证流程6 Mapper XML能够被MyBatis解析并注册关键状态机语句。
 */
class SeckillCouponClaimMapperXmlTests {

    /**
     * 流程6A/B失败状态机Mapper XML应能被MyBatis完整解析并注册关键语句。
     */
    @Test
    void shouldParseFailureStateMachineMapperXml() throws IOException {
        Configuration configuration = parseMapper(
                "mapper/seckill/SeckillCouponClaimFailureMapper.xml");

        assertTrue(configuration.hasStatement(
                "com.sky.seckill.mapper.SeckillCouponClaimFailureMapper.completeRepair"));
        assertTrue(configuration.hasStatement(
                "com.sky.seckill.mapper.SeckillCouponClaimFailureMapper.countUnresolvedByCouponId"));
    }

    /**
     * 流程6C活动结算Mapper XML应能被MyBatis完整解析并注册关键语句。
     */
    @Test
    void shouldParseSettlementMapperXml() throws IOException {
        Configuration configuration = parseMapper(
                "mapper/seckill/SeckillCouponClaimSettlementMapper.xml");

        assertTrue(configuration.hasStatement(
                "com.sky.seckill.mapper.SeckillCouponClaimSettlementMapper.createPendingForEndedCoupons"));
        assertTrue(configuration.hasStatement(
                "com.sky.seckill.mapper.SeckillCouponClaimSettlementMapper.complete"));
    }

    /**
     * 从测试类路径读取并解析指定MyBatis Mapper资源。
     */
    private Configuration parseMapper(String resource) throws IOException {
        Configuration configuration = new Configuration();
        try (InputStream inputStream = Resources.getResourceAsStream(resource)) {
            XMLMapperBuilder mapperBuilder = new XMLMapperBuilder(
                    inputStream,
                    configuration,
                    resource,
                    configuration.getSqlFragments()
            );
            mapperBuilder.parse();
        }
        return configuration;
    }
}
