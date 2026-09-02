package com.example.aiapp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.aiapp.domain.entity.RefundOrderEntity;
import com.example.aiapp.domain.vo.BillingRefundReadVO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface RefundOrderMapper extends BaseMapper<RefundOrderEntity> {

    @Select("""
            SELECT r.refund_no, r.status, r.currency, r.amount_minor,
                   r.reason_code, r.reason_note, r.succeeded_at, r.created_at,
                   o.order_no, o.product_snapshot
            FROM refund_order r
            JOIN billing_order o ON o.id = r.order_id
            WHERE r.user_id = #{userId}
            ORDER BY r.created_at DESC
            """)
    List<BillingRefundReadVO> selectUserRefunds(@Param("userId") long userId);
}
