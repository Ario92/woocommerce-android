package com.woocommerce.android.model

import android.os.Parcelable
import com.woocommerce.android.extensions.CASH_PAYMENTS
import com.woocommerce.android.extensions.fastStripHtml
import com.woocommerce.android.extensions.roundError
import com.woocommerce.android.model.Order.Item
import com.woocommerce.android.model.Order.OrderStatus
import com.woocommerce.android.ui.products.ProductHelper
import com.woocommerce.android.util.AddressUtils
import kotlinx.android.parcel.IgnoredOnParcel
import kotlinx.android.parcel.Parcelize
import org.wordpress.android.fluxc.model.WCOrderModel
import org.wordpress.android.fluxc.model.WCOrderStatusModel
import org.wordpress.android.fluxc.model.order.OrderIdentifier
import org.wordpress.android.fluxc.network.rest.wpcom.wc.order.CoreOrderStatus
import org.wordpress.android.util.DateTimeUtils
import java.math.BigDecimal
import java.util.Date

@Parcelize
data class Order(
    val identifier: OrderIdentifier,
    val remoteId: Long,
    val number: String,
    val localSiteId: Int,
    val dateCreated: Date,
    val dateModified: Date,
    val datePaid: Date?,
    val status: CoreOrderStatus,
    val total: BigDecimal,
    val productsTotal: BigDecimal,
    val totalTax: BigDecimal,
    val shippingTotal: BigDecimal,
    val discountTotal: BigDecimal,
    val refundTotal: BigDecimal,
    val currency: String,
    val customerNote: String,
    val discountCodes: String,
    val paymentMethod: String,
    val paymentMethodTitle: String,
    val isCashPayment: Boolean,
    val pricesIncludeTax: Boolean,
    val multiShippingLinesAvailable: Boolean,
    val billingAddress: Address,
    val shippingAddress: Address,
    val shippingMethodList: List<String?>,
    val items: List<Item>
) : Parcelable {
    @IgnoredOnParcel
    val isOrderPaid = paymentMethodTitle.isEmpty() && datePaid == null

    @IgnoredOnParcel
    val isAwaitingPayment = status == CoreOrderStatus.PENDING ||
        status == CoreOrderStatus.ON_HOLD || datePaid == null

    @IgnoredOnParcel
    val isRefundAvailable = refundTotal < total

    @IgnoredOnParcel
    val availableRefundQuantity = items.sumBy { it.quantity }

    @Parcelize
    data class OrderStatus(
        val statusKey: String,
        val label: String
    ) : Parcelable

    @Parcelize
    data class Item(
        val itemId: Long,
        val productId: Long,
        val name: String,
        val price: BigDecimal,
        val sku: String,
        val quantity: Int,
        val subtotal: BigDecimal,
        val totalTax: BigDecimal,
        val total: BigDecimal,
        val variationId: Long
    ) : Parcelable {
        @IgnoredOnParcel
        val uniqueId: Long = ProductHelper.productOrVariationId(productId, variationId)
    }

    fun getBillingName(defaultValue: String): String {
        return if (billingAddress.firstName.isEmpty() && billingAddress.lastName.isEmpty()) {
            defaultValue
        } else "${billingAddress.firstName} ${billingAddress.lastName}"
    }

    fun formatBillingInformationForDisplay(): String {
        val billingName = getBillingName("")
        val billingAddress = this.billingAddress.getEnvelopeAddress()
        val billingCountry = AddressUtils.getCountryLabelByCountryCode(this.billingAddress.country)
        return this.billingAddress.getFullAddress(
            billingName, billingAddress, billingCountry
        )
    }

    fun formatShippingInformationForDisplay(): String {
        val shippingName = "${shippingAddress.firstName} ${shippingAddress.lastName}"
        val shippingAddress = this.shippingAddress.getEnvelopeAddress()
        val shippingCountry = AddressUtils.getCountryLabelByCountryCode(this.shippingAddress.country)
        return this.shippingAddress.getFullAddress(
            shippingName, shippingAddress, shippingCountry
        )
    }
}

fun WCOrderModel.toAppModel(): Order {
    return Order(
            OrderIdentifier(this),
            this.remoteOrderId,
            this.number,
            this.localSiteId,
            DateTimeUtils.dateUTCFromIso8601(this.dateCreated) ?: Date(),
            DateTimeUtils.dateUTCFromIso8601(this.dateModified) ?: Date(),
            DateTimeUtils.dateUTCFromIso8601(this.datePaid),
            CoreOrderStatus.fromValue(this.status) ?: CoreOrderStatus.PENDING,
            this.total.toBigDecimalOrNull()?.roundError() ?: BigDecimal.ZERO,
            this.getOrderSubtotal().toBigDecimal().roundError(),
            this.totalTax.toBigDecimalOrNull()?.roundError() ?: BigDecimal.ZERO,
            this.shippingTotal.toBigDecimalOrNull()?.roundError() ?: BigDecimal.ZERO,
            this.discountTotal.toBigDecimalOrNull()?.roundError() ?: BigDecimal.ZERO,
            -this.refundTotal.toBigDecimal().roundError(), // WCOrderModel.refundTotal is NEGATIVE
            this.currency,
            this.customerNote,
            this.discountCodes,
            this.paymentMethod,
            this.paymentMethodTitle,
            CASH_PAYMENTS.contains(this.paymentMethod),
            this.pricesIncludeTax,
            this.isMultiShippingLinesAvailable(),
            this.getBillingAddress().let {
                Address(
                        it.company,
                        it.firstName,
                        it.lastName,
                        this.billingPhone,
                        it.country,
                        it.state,
                        it.address1,
                        it.address2,
                        it.city,
                        it.postcode,
                        this.billingEmail
                )
            },
            this.getShippingAddress().let {
                Address(
                    it.company,
                    it.firstName,
                    it.lastName,
                    "",
                    it.country,
                    it.state,
                    it.address1,
                    it.address2,
                    it.city,
                    it.postcode,
                    ""
                )
            },
            getShippingLineList().map { it.methodTitle },
            getLineItemList()
                    .filter { it.productId != null && it.id != null }
                    .map {
                        Item(
                                it.id!!,
                                it.productId!!,
                                it.name?.fastStripHtml() ?: "",
                                it.price?.toBigDecimalOrNull()?.roundError() ?: BigDecimal.ZERO,
                                it.sku ?: "",
                                it.quantity?.toInt() ?: 0,
                                it.subtotal?.toBigDecimalOrNull()?.roundError() ?: BigDecimal.ZERO,
                                it.totalTax?.toBigDecimalOrNull()?.roundError() ?: BigDecimal.ZERO,
                                it.total?.toBigDecimalOrNull()?.roundError() ?: BigDecimal.ZERO,
                                it.variationId ?: 0
                        )
                    }
    )
}

fun WCOrderStatusModel.toOrderStatus(): OrderStatus {
    return OrderStatus(
        this.statusKey,
        this.label
    )
}
