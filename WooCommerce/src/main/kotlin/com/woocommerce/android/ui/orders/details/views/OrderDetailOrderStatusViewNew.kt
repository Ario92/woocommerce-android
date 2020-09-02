package com.woocommerce.android.ui.orders.details.views

import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.View
import com.google.android.material.card.MaterialCardView
import com.woocommerce.android.R
import com.woocommerce.android.extensions.getMediumDate
import com.woocommerce.android.extensions.getTimeString
import com.woocommerce.android.extensions.isToday
import com.woocommerce.android.model.Order
import com.woocommerce.android.model.Order.OrderStatus
import com.woocommerce.android.ui.orders.OrderStatusTag
import com.woocommerce.android.widgets.tags.TagView
import kotlinx.android.synthetic.main.order_detail_order_status.view.*

class OrderDetailOrderStatusViewNew @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : MaterialCardView(ctx, attrs, defStyleAttr) {
    init {
        View.inflate(context, R.layout.order_detail_order_status, this)
    }

    fun updateStatus(orderStatus: OrderStatus) {
        orderStatus_orderTags.removeAllViews()
        orderStatus_orderTags.addView(getTagView(orderStatus))
    }

    fun updateOrder(order: Order) {
        val dateStr = if (order.dateCreated.isToday()) {
            order.dateCreated.getTimeString(context)
        } else {
            order.dateCreated.getMediumDate(context)
        }
        orderStatus_dateAndOrderNum.text = context.getString(
            R.string.orderdetail_orderstatus_date_and_ordernum,
            dateStr,
            order.number
        )

        orderStatus_name.text = order.getBillingName(context.getString(R.string.orderdetail_customer_name_default))
    }

    private fun getTagView(orderStatus: OrderStatus): TagView {
        val orderTag = OrderStatusTag(orderStatus)
        val tagView = TagView(context)
        tagView.tag = orderTag
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            tagView.isFocusableInTouchMode = true
        } else {
            tagView.focusable = View.FOCUSABLE
        }
        return tagView
    }
}
