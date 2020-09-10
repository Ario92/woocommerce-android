package com.woocommerce.android.ui.orders.details

import android.os.Parcelable
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.squareup.inject.assisted.Assisted
import com.squareup.inject.assisted.AssistedInject
import com.woocommerce.android.R.string
import com.woocommerce.android.annotations.OpenClassOnDebug
import com.woocommerce.android.di.ViewModelAssistedFactory
import com.woocommerce.android.extensions.whenNotNullNorEmpty
import com.woocommerce.android.model.Order
import com.woocommerce.android.model.Order.OrderStatus
import com.woocommerce.android.model.OrderNote
import com.woocommerce.android.model.OrderShipmentTracking
import com.woocommerce.android.model.Refund
import com.woocommerce.android.model.RequestResult
import com.woocommerce.android.model.ShippingLabel
import com.woocommerce.android.model.getNonRefundedProducts
import com.woocommerce.android.model.hasNonRefundedProducts
import com.woocommerce.android.model.loadProducts
import com.woocommerce.android.tools.NetworkStatus
import com.woocommerce.android.util.CoroutineDispatchers
import com.woocommerce.android.util.FeatureFlag
import com.woocommerce.android.viewmodel.LiveDataDelegate
import com.woocommerce.android.viewmodel.MultiLiveEvent.Event.ShowSnackbar
import com.woocommerce.android.viewmodel.ResourceProvider
import com.woocommerce.android.viewmodel.SavedStateWithArgs
import com.woocommerce.android.viewmodel.ScopedViewModel
import kotlinx.android.parcel.Parcelize
import kotlinx.coroutines.launch
import org.wordpress.android.fluxc.model.order.OrderIdSet
import org.wordpress.android.fluxc.model.order.toIdSet

@OpenClassOnDebug
class OrderDetailViewModelNew @AssistedInject constructor(
    @Assisted savedState: SavedStateWithArgs,
    dispatchers: CoroutineDispatchers,
    private val networkStatus: NetworkStatus,
    private val resourceProvider: ResourceProvider,
    private val orderDetailRepository: OrderDetailRepositoryNew
) : ScopedViewModel(savedState, dispatchers) {
    private val navArgs: OrderDetailFragmentNewArgs by savedState.navArgs()

    private val orderIdSet: OrderIdSet
        get() = navArgs.orderId.toIdSet()

    final val orderDetailViewStateData = LiveDataDelegate(savedState, OrderDetailViewState())
    private var orderDetailViewState by orderDetailViewStateData

    private val _orderNotes = MutableLiveData<List<OrderNote>>()
    val orderNotes: LiveData<List<OrderNote>> = _orderNotes

    private val _orderRefunds = MutableLiveData<List<Refund>>()
    val orderRefunds: LiveData<List<Refund>> = _orderRefunds

    private val _productList = MutableLiveData<List<Order.Item>>()
    val productList: LiveData<List<Order.Item>> = _productList

    private val _shipmentTrackings = MutableLiveData<List<OrderShipmentTracking>>()
    val shipmentTrackings: LiveData<List<OrderShipmentTracking>> = _shipmentTrackings

    private val _shippingLabels = MutableLiveData<List<ShippingLabel>>()
    val shippingLabels: LiveData<List<ShippingLabel>> = _shippingLabels

    val order: Order?
    get() = orderDetailViewState.order

    override fun onCleared() {
        super.onCleared()
        orderDetailRepository.onCleanup()
    }

    fun loadOrderDetail() {
        launch {
            orderDetailRepository.getOrder(navArgs.orderId)?.let { orderInDb ->
                updateOrderState(orderInDb)
                loadOrderNotes()
                loadOrderRefunds()
                loadShipmentTrackings()
                loadOrderShippingLabels()
            } ?: fetchOrder()
        }
    }

    fun hasVirtualProductsOnly(): Boolean {
        return orderDetailViewState.order?.items?.let { lineItems ->
            val remoteProductIds = lineItems.map { it.productId }
            orderDetailRepository.getProductsByRemoteIds(remoteProductIds).any { it.virtual }
        } ?: false
    }

    private suspend fun fetchOrder() {
        if (networkStatus.isConnected()) {
            orderDetailViewState = orderDetailViewState.copy(isOrderDetailSkeletonShown = true)
            val fetchedOrder = orderDetailRepository.fetchOrder(navArgs.orderId)
            if (fetchedOrder != null) {
                updateOrderState(fetchedOrder)
                loadOrderNotes()
                loadOrderRefunds()
                loadShipmentTrackings()
                loadOrderShippingLabels()
            } else {
                triggerEvent(ShowSnackbar(string.order_error_fetch_generic))
            }
        } else {
            triggerEvent(ShowSnackbar(string.offline_error))
            orderDetailViewState = orderDetailViewState.copy(isOrderDetailSkeletonShown = false)
        }
    }

    private fun updateOrderState(order: Order) {
        val orderStatus = orderDetailRepository.getOrderStatus(order.status.value)
        orderDetailViewState = orderDetailViewState.copy(
            order = order,
            orderStatus = orderStatus,
            toolbarTitle = resourceProvider.getString(
                string.orderdetail_orderstatus_ordernum, order.number
            )
        )
        loadOrderProducts()
    }

    private suspend fun loadOrderNotes() {
        if (networkStatus.isConnected()) {
            orderDetailViewState = orderDetailViewState.copy(isOrderNotesSkeletonShown = true)
            if (!orderDetailRepository.fetchOrderNotes(orderIdSet.id, orderIdSet.remoteOrderId)) {
                triggerEvent(ShowSnackbar(string.order_error_fetch_notes_generic))
            }
            // fetch order notes from the local db and hide the skeleton view
            _orderNotes.value = orderDetailRepository.getOrderNotes(orderIdSet.id)
            orderDetailViewState = orderDetailViewState.copy(isOrderNotesSkeletonShown = false)
        } else {
            triggerEvent(ShowSnackbar(string.offline_error))
        }
    }

    private suspend fun loadOrderRefunds() {
        _orderRefunds.value = orderDetailRepository.getOrderRefunds(orderIdSet.remoteOrderId)
        if (networkStatus.isConnected()) {
            _orderRefunds.value = orderDetailRepository.fetchOrderRefunds(orderIdSet.remoteOrderId)
        }

        // display products only if there are some non refunded items in the list
        loadOrderProducts()
    }

    private fun loadOrderProducts() {
        _productList.value = order?.let { order ->
            _orderRefunds.value?.let { refunds ->
                if (refunds.hasNonRefundedProducts(order.items)) {
                    refunds.getNonRefundedProducts(order.items)
                } else emptyList()
            } ?: order.items
        } ?: emptyList()
    }

    private suspend fun loadShipmentTrackings() {
        when (orderDetailRepository.fetchOrderShipmentTrackingList(orderIdSet.id, orderIdSet.remoteOrderId)) {
            RequestResult.SUCCESS -> {
                _shipmentTrackings.value = orderDetailRepository.getOrderShipmentTrackings(orderIdSet.id)
                orderDetailViewState = orderDetailViewState.copy(isShipmentTrackingAvailable = true)
            }
            else -> {
                orderDetailViewState = orderDetailViewState.copy(isShipmentTrackingAvailable = false)
                _shipmentTrackings.value = emptyList()
            }
        }
    }

    private suspend fun loadOrderShippingLabels() {
        order?.let { order ->
            if (FeatureFlag.SHIPPING_LABELS_M1.isEnabled()) {
                orderDetailRepository.getOrderShippingLabels(orderIdSet.remoteOrderId)
                    .whenNotNullNorEmpty { _shippingLabels.value = it.loadProducts(order.items) }

                _shippingLabels.value = orderDetailRepository
                        .fetchOrderShippingLabels(orderIdSet.remoteOrderId)
                        .loadProducts(order.items)
            } else {
                _shippingLabels.value = emptyList()
            }
        }

        // hide the shipment tracking section and the product list section if
        // shipping labels are available for the order
        _shippingLabels.value?.whenNotNullNorEmpty {
            _productList.value = emptyList()
            _shipmentTrackings.value = emptyList()
            orderDetailViewState = orderDetailViewState.copy(isShipmentTrackingAvailable = false)
        }
    }

    @Parcelize
    data class OrderDetailViewState(
        val order: Order? = null,
        val toolbarTitle: String? = null,
        val orderStatus: OrderStatus? = null,
        val isOrderDetailSkeletonShown: Boolean? = null,
        val isOrderNotesSkeletonShown: Boolean? = null,
        val isRefreshing: Boolean? = null,
        val isShipmentTrackingAvailable: Boolean? = null
    ) : Parcelable

    @AssistedInject.Factory
    interface Factory : ViewModelAssistedFactory<OrderDetailViewModelNew>
}
