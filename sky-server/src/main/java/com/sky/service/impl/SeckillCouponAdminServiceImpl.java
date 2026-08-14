package com.sky.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.StatusConstant;
import com.sky.dto.SeckillCouponDTO;
import com.sky.dto.SeckillCouponPageQueryDTO;
import com.sky.entity.SeckillCoupon;
import com.sky.event.SeckillCouponChangedEvent;
import com.sky.exception.CouponBusinessException;
import com.sky.mapper.SeckillCouponMapper;
import com.sky.result.PageResult;
import com.sky.service.SeckillCouponAdminService;
import com.sky.service.support.SeckillCouponFinder;
import com.sky.service.support.SeckillCouponValidator;
import com.sky.vo.SeckillCouponVO;
import org.springframework.beans.BeanUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 管理端秒杀券用例实现。
 */
@Service
public class SeckillCouponAdminServiceImpl implements SeckillCouponAdminService {

    private final SeckillCouponMapper seckillCouponMapper;
    private final SeckillCouponFinder seckillCouponFinder;
    private final SeckillCouponValidator seckillCouponValidator;
    private final ApplicationEventPublisher applicationEventPublisher;

    public SeckillCouponAdminServiceImpl(SeckillCouponMapper seckillCouponMapper, SeckillCouponFinder seckillCouponFinder,
            SeckillCouponValidator seckillCouponValidator, ApplicationEventPublisher applicationEventPublisher) {
        this.seckillCouponMapper = seckillCouponMapper;
        this.seckillCouponFinder = seckillCouponFinder;
        this.seckillCouponValidator = seckillCouponValidator;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    /**
     * 新增秒杀券，并在事务提交后触发缓存同步。
     */
    @Override
    @Transactional
    public void save(SeckillCouponDTO seckillCouponDTO) {
        seckillCouponValidator.validateForSave(seckillCouponDTO);

        SeckillCoupon seckillCoupon = new SeckillCoupon();
        BeanUtils.copyProperties(seckillCouponDTO, seckillCoupon);
        seckillCoupon.setId(null);
        seckillCoupon.setName(seckillCouponDTO.getName().trim());
        seckillCoupon.setRemainingStock(seckillCouponDTO.getTotalStock());
        seckillCoupon.setStatus(StatusConstant.DISABLE);

        seckillCouponMapper.insert(seckillCoupon);
        publishChangedEvent(seckillCoupon.getId(), SeckillCouponChangedEvent.ChangeType.CREATED);
    }

    /**
     * 分页查询管理端秒杀券。
     */
    @Override
    public PageResult pageQuery(SeckillCouponPageQueryDTO queryDTO) {
        if (queryDTO == null || queryDTO.getPage() <= 0 || queryDTO.getPageSize() <= 0) {
            throw new CouponBusinessException("页码和每页记录数必须大于0");
        }

        PageHelper.startPage(queryDTO.getPage(), queryDTO.getPageSize());
        Page<SeckillCouponVO> page = seckillCouponMapper.pageQuery(queryDTO);
        return new PageResult(page.getTotal(), page.getResult());
    }

    /**
     * 修改停用状态的秒杀券，并保留已领取库存。
     */
    @Override
    @Transactional
    public void update(SeckillCouponDTO seckillCouponDTO) {
        seckillCouponValidator.validateForUpdate(seckillCouponDTO);

        SeckillCoupon currentCoupon =
                seckillCouponFinder.getByIdOrThrow(seckillCouponDTO.getId());
        if (StatusConstant.ENABLE.equals(currentCoupon.getStatus())) {
            throw new CouponBusinessException("启用中的秒杀券不能修改，请先停用");
        }

        int claimedStock = currentCoupon.getTotalStock() - currentCoupon.getRemainingStock();
        if (seckillCouponDTO.getTotalStock() < claimedStock) {
            throw new CouponBusinessException("总库存不能小于已领取数量：" + claimedStock);
        }

        SeckillCoupon seckillCoupon = new SeckillCoupon();
        BeanUtils.copyProperties(seckillCouponDTO, seckillCoupon);
        seckillCoupon.setName(seckillCouponDTO.getName().trim());
        seckillCoupon.setRemainingStock(seckillCouponDTO.getTotalStock() - claimedStock);

        seckillCouponMapper.update(seckillCoupon);
        publishChangedEvent(seckillCoupon.getId(), SeckillCouponChangedEvent.ChangeType.UPDATED);
    }

    /**
     * 启用或停用秒杀券。
     */
    @Override
    @Transactional
    public void startOrStop(Integer status, Long id) {
        if (id == null) {
            throw new CouponBusinessException("秒杀券ID不能为空");
        }
        if (!StatusConstant.ENABLE.equals(status)
                && !StatusConstant.DISABLE.equals(status)) {
            throw new CouponBusinessException("秒杀券状态只能是0或1");
        }

        SeckillCoupon currentCoupon = seckillCouponFinder.getByIdOrThrow(id);
        if (status.equals(currentCoupon.getStatus())) {
            publishChangedEvent(id, SeckillCouponChangedEvent.ChangeType.ACTIVITY_REPAIR_REQUESTED);
            return;
        }

        if (StatusConstant.ENABLE.equals(status)) {
            if (currentCoupon.getRemainingStock() == null || currentCoupon.getRemainingStock() <= 0) {
                throw new CouponBusinessException("剩余库存不足，不能启用秒杀券");
            }
            if (currentCoupon.getClaimEndTime() == null || !currentCoupon.getClaimEndTime().isAfter(LocalDateTime.now())) {
                throw new CouponBusinessException("领取时间已结束，不能启用秒杀券");
            }
        }

        SeckillCoupon seckillCoupon = SeckillCoupon.builder()
                .id(id)
                .status(status)
                .build();
        seckillCouponMapper.update(seckillCoupon);
        publishChangedEvent(id, SeckillCouponChangedEvent.ChangeType.STATUS_CHANGED);
    }

    /**
     * 发布秒杀券变更事件，由事务提交后监听器更新Redis。
     */
    private void publishChangedEvent(Long couponId, SeckillCouponChangedEvent.ChangeType changeType) {
        applicationEventPublisher.publishEvent(new SeckillCouponChangedEvent(couponId, changeType));
    }
}
