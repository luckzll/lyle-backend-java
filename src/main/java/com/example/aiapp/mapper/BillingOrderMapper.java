package com.example.aiapp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.aiapp.domain.entity.BillingOrderEntity;
import com.example.aiapp.domain.vo.BillingOrderReadVO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface BillingOrderMapper extends BaseMapper<BillingOrderEntity> {

    @Select("""
            SELECT o.order_no, o.status, o.order_type, o.product_code,
                   o.price_version_code, o.product_snapshot, o.currency,
                   o.payable_amount_minor, o.paid_amount_minor, o.expires_at,
                   o.paid_at, o.fulfilled_at, o.created_at,
                   (SELECT p.payment_no FROM payment_attempt p
                    WHERE p.order_id = o.id ORDER BY p.created_at DESC, p.id DESC LIMIT 1) AS payment_no,
                   (SELECT p.status FROM payment_attempt p
                    WHERE p.order_id = o.id ORDER BY p.created_at DESC, p.id DESC LIMIT 1) AS payment_status,
                   (SELECT p.provider FROM payment_attempt p
                    WHERE p.order_id = o.id ORDER BY p.created_at DESC, p.id DESC LIMIT 1) AS payment_provider,
                   (SELECT r.status FROM refund_order r
                    WHERE r.order_id = o.id ORDER BY r.created_at DESC, r.id DESC LIMIT 1) AS refund_status,
                   (SELECT r.succeeded_at FROM refund_order r
                    WHERE r.order_id = o.id ORDER BY r.created_at DESC, r.id DESC LIMIT 1) AS refund_succeeded_at
            FROM billing_order o
            WHERE o.user_id = #{userId}
            ORDER BY o.created_at DESC
            LIMIT #{limit}
            """)
    List<BillingOrderReadVO> selectUserOrders(
            @Param("userId") long userId,
            @Param("limit") int limit
    );

    @Select("""
            SELECT o.order_no, o.status, o.order_type, o.product_code,
                   o.price_version_code, o.product_snapshot, o.currency,
                   o.payable_amount_minor, o.paid_amount_minor, o.expires_at,
                   o.paid_at, o.fulfilled_at, o.created_at,
                   (SELECT p.payment_no FROM payment_attempt p
                    WHERE p.order_id = o.id ORDER BY p.created_at DESC, p.id DESC LIMIT 1) AS payment_no,
                   (SELECT p.status FROM payment_attempt p
                    WHERE p.order_id = o.id ORDER BY p.created_at DESC, p.id DESC LIMIT 1) AS payment_status,
                   (SELECT p.provider FROM payment_attempt p
                    WHERE p.order_id = o.id ORDER BY p.created_at DESC, p.id DESC LIMIT 1) AS payment_provider,
                   (SELECT r.status FROM refund_order r
                    WHERE r.order_id = o.id ORDER BY r.created_at DESC, r.id DESC LIMIT 1) AS refund_status,
                   (SELECT r.succeeded_at FROM refund_order r
                    WHERE r.order_id = o.id ORDER BY r.created_at DESC, r.id DESC LIMIT 1) AS refund_succeeded_at
            FROM billing_order o
            WHERE o.order_no = #{orderNo} AND o.user_id = #{userId}
            """)
    BillingOrderReadVO selectUserOrder(
            @Param("userId") long userId,
            @Param("orderNo") String orderNo
    );
}
