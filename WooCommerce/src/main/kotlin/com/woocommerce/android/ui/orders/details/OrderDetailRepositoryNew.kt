package com.woocommerce.android.ui.orders.details

import com.woocommerce.android.annotations.OpenClassOnDebug
import com.woocommerce.android.model.Order
import com.woocommerce.android.model.Order.OrderStatus
import com.woocommerce.android.model.toAppModel
import com.woocommerce.android.model.toOrderStatus
import com.woocommerce.android.tools.SelectedSite
import com.woocommerce.android.util.WooLog
import com.woocommerce.android.util.WooLog.T.ORDERS
import com.woocommerce.android.util.suspendCancellableCoroutineWithTimeout
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode.MAIN
import org.wordpress.android.fluxc.Dispatcher
import org.wordpress.android.fluxc.action.WCOrderAction
import org.wordpress.android.fluxc.generated.WCOrderActionBuilder
import org.wordpress.android.fluxc.model.WCOrderStatusModel
import org.wordpress.android.fluxc.model.order.OrderIdentifier
import org.wordpress.android.fluxc.model.order.toIdSet
import org.wordpress.android.fluxc.store.WCOrderStore
import org.wordpress.android.fluxc.store.WCOrderStore.FetchOrderNotesPayload
import org.wordpress.android.fluxc.store.WCOrderStore.OnOrderChanged
import org.wordpress.android.fluxc.store.WCProductStore
import javax.inject.Inject
import kotlin.coroutines.resume

@OpenClassOnDebug
class OrderDetailRepositoryNew @Inject constructor(
    private val dispatcher: Dispatcher,
    private val orderStore: WCOrderStore,
    private val productStore: WCProductStore,
    private val selectedSite: SelectedSite
) {
    companion object {
        private const val ACTION_TIMEOUT = 10L * 1000
    }

    private var continuationFetchOrder: CancellableContinuation<Boolean>? = null
    private var continuationFetchOrderNotes: CancellableContinuation<Boolean>? = null

    init {
        dispatcher.register(this)
    }

    fun onCleanup() {
        dispatcher.unregister(this)
    }

    suspend fun fetchOrder(orderIdentifier: OrderIdentifier): Order? {
        val remoteOrderId = orderIdentifier.toIdSet().remoteOrderId
        try {
            continuationFetchOrder?.cancel()
            suspendCancellableCoroutineWithTimeout<Boolean>(ACTION_TIMEOUT) {
                continuationFetchOrder = it

                val payload = WCOrderStore.FetchSingleOrderPayload(selectedSite.get(), remoteOrderId)
                dispatcher.dispatch(WCOrderActionBuilder.newFetchSingleOrderAction(payload))
            }
        } catch (e: CancellationException) {
            WooLog.e(ORDERS, "CancellationException while fetching single order $remoteOrderId")
        }

        continuationFetchOrder = null
        return getOrder(orderIdentifier)
    }

    suspend fun fetchOrderNotes(
        localOrderId: Int,
        remoteOrderId: Long
    ): Boolean {
        return try {
            continuationFetchOrderNotes?.cancel()
            suspendCancellableCoroutineWithTimeout<Boolean>(ACTION_TIMEOUT) {
                continuationFetchOrderNotes = it

                val payload = FetchOrderNotesPayload(localOrderId, remoteOrderId, selectedSite.get())
                dispatcher.dispatch(WCOrderActionBuilder.newFetchOrderNotesAction(payload))
            } ?: false
        } catch (e: CancellationException) {
            WooLog.e(ORDERS, "CancellationException while fetching order notes $remoteOrderId")
            false
        }
    }

    fun getOrder(orderIdentifier: OrderIdentifier) = orderStore.getOrderByIdentifier(orderIdentifier)?.toAppModel()

    fun getOrderStatus(key: String): OrderStatus {
        return (orderStore.getOrderStatusForSiteAndKey(selectedSite.get(), key) ?: WCOrderStatusModel().apply {
                statusKey = key
                label = key
        }).toOrderStatus()
    }

    fun getOrderNotes(localOrderId: Int) =
        orderStore.getOrderNotesForOrder(localOrderId).map { it.toAppModel() }

    fun getProductsByRemoteIds(remoteIds: List<Long>) =
        productStore.getProductsByRemoteIds(selectedSite.get(), remoteIds)

    @Suppress("unused")
    @Subscribe(threadMode = MAIN)
    fun onOrderChanged(event: OnOrderChanged) {
        when (event.causeOfChange) {
            WCOrderAction.FETCH_SINGLE_ORDER -> {
                if (event.isError) {
                    continuationFetchOrder?.resume(false)
                } else {
                    continuationFetchOrder?.resume(true)
                }
            }
            WCOrderAction.FETCH_ORDER_NOTES -> {
                if (event.isError) {
                    continuationFetchOrderNotes?.resume(false)
                } else {
                    continuationFetchOrderNotes?.resume(true)
                }
                continuationFetchOrderNotes = null
            }
            else -> { }
        }
    }
}
