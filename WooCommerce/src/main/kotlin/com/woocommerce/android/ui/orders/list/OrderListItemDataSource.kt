package com.woocommerce.android.ui.orders.list

import com.woocommerce.android.model.TimeGroup
import com.woocommerce.android.model.TimeGroup.GROUP_FUTURE
import com.woocommerce.android.model.TimeGroup.GROUP_OLDER_MONTH
import com.woocommerce.android.model.TimeGroup.GROUP_OLDER_TWO_DAYS
import com.woocommerce.android.model.TimeGroup.GROUP_OLDER_WEEK
import com.woocommerce.android.model.TimeGroup.GROUP_TODAY
import com.woocommerce.android.model.TimeGroup.GROUP_YESTERDAY
import com.woocommerce.android.tools.NetworkStatus
import com.woocommerce.android.ui.orders.list.OrderListItemIdentifier.OrderIdentifier
import com.woocommerce.android.ui.orders.list.OrderListItemIdentifier.SectionHeaderIdentifier
import com.woocommerce.android.ui.orders.list.OrderListItemUIType.LoadingItem
import com.woocommerce.android.ui.orders.list.OrderListItemUIType.OrderListItemUI
import com.woocommerce.android.ui.orders.list.OrderListItemUIType.SectionHeader
import org.wordpress.android.fluxc.Dispatcher
import org.wordpress.android.fluxc.generated.WCOrderActionBuilder
import org.wordpress.android.fluxc.model.LocalOrRemoteId.RemoteId
import org.wordpress.android.fluxc.model.WCOrderListDescriptor
import org.wordpress.android.fluxc.model.WCOrderModel
import org.wordpress.android.fluxc.model.WCOrderSummaryModel
import org.wordpress.android.fluxc.model.list.datasource.ListItemDataSourceInterface
import org.wordpress.android.fluxc.store.WCOrderStore
import org.wordpress.android.fluxc.store.WCOrderStore.FetchOrderListPayload
import org.wordpress.android.fluxc.model.list.datasource.InternalPagedListDataSource
import org.wordpress.android.util.DateTimeUtils
import java.util.Date

/**
 * Works with a [androidx.paging.PagedList] by providing the logic needed to fetch the data used to populate
 * the order list view.
 *
 * @see [ListItemDataSourceInterface] and [InternalPagedListDataSource] in FluxC to get a better understanding
 * of how this works with the underlying internal list management code.
 */
class OrderListItemDataSource(
    private val dispatcher: Dispatcher,
    private val orderStore: WCOrderStore,
    private val networkStatus: NetworkStatus,
    private val fetcher: OrderFetcher
) : ListItemDataSourceInterface<WCOrderListDescriptor, OrderListItemIdentifier, OrderListItemUIType> {
    override fun getItemsAndFetchIfNecessary(
        listDescriptor: WCOrderListDescriptor,
        itemIdentifiers: List<OrderListItemIdentifier>
    ): List<OrderListItemUIType> {
        val remoteItemIds = itemIdentifiers.mapNotNull { (it as? OrderIdentifier)?.remoteId }
        val ordersMap: Map<RemoteId, WCOrderModel> = if (!networkStatus.isConnected()) {
            orderStore.getOrdersForDescriptor(listDescriptor).associateBy { RemoteId(it.remoteOrderId) }
        } else {
            orderStore.getOrdersByRemoteOrderId(listDescriptor.site, remoteItemIds).also { ordersMap ->
                // Fetch missing items
                fetcher.fetchOrders(
                        site = listDescriptor.site,
                        remoteItemIds = remoteItemIds.filter { !ordersMap.containsKey(it) }
                )
            }
        }

        val mapSummary = { remoteOrderId: RemoteId ->
            ordersMap[remoteOrderId].let { order ->
                if (order == null) {
                    LoadingItem(remoteOrderId)
                } else {
                    OrderListItemUI(
                            remoteOrderId = RemoteId(order.remoteOrderId),
                            orderNumber = order.number,
                            orderName = "${order.billingFirstName} ${order.billingLastName}",
                            orderTotal = order.total,
                            status = order.status,
                            dateCreated = order.dateCreated,
                            currencyCode = order.currency
                    )
                }
            }
        }

        return itemIdentifiers.map { identifier ->
            when (identifier) {
                is OrderIdentifier -> mapSummary(identifier.remoteId)
                is SectionHeaderIdentifier -> SectionHeader(title = identifier.title)
            }
        }
    }

    override fun getItemIdentifiers(
        listDescriptor: WCOrderListDescriptor,
        remoteItemIds: List<RemoteId>,
        isListFullyFetched: Boolean
    ): List<OrderListItemIdentifier> {
        val orderSummaries = if (!networkStatus.isConnected()) {
            orderStore.getOrdersForDescriptor(listDescriptor).map { orderModel ->
                WCOrderSummaryModel().apply {
                    localSiteId = orderModel.localSiteId
                    remoteOrderId = orderModel.remoteOrderId
                    dateCreated = orderModel.dateCreated
                }
            }
        } else {
            orderStore
                    .getOrderSummariesByRemoteOrderIds(listDescriptor.site, remoteItemIds)
                    .let { summariesByRemoteId ->
                        remoteItemIds.mapNotNull { summariesByRemoteId[it] }
                    }
        }

        val listFuture = mutableListOf<OrderIdentifier>()
        val listToday = mutableListOf<OrderIdentifier>()
        val listYesterday = mutableListOf<OrderIdentifier>()
        val listTwoDays = mutableListOf<OrderIdentifier>()
        val listWeek = mutableListOf<OrderIdentifier>()
        val listMonth = mutableListOf<OrderIdentifier>()
        val mapToRemoteOrderIdentifier = { summary: WCOrderSummaryModel ->
            OrderIdentifier(RemoteId(summary.remoteOrderId))
        }
        orderSummaries.forEach {
            // Default to today if the date cannot be parsed. This date is in UTC.
            val date: Date = DateTimeUtils.dateUTCFromIso8601(it.dateCreated) ?: DateTimeUtils.nowUTC()

            // Check if future-dated orders should be excluded from the results list.
            if (listDescriptor.excludeFutureOrders) {
                val currentUtcDate = DateTimeUtils.nowUTC()
                if (date.after(currentUtcDate)) {
                    // This order is dated for the future so skip adding it to the list
                    return@forEach
                }
            }

            when (TimeGroup.getTimeGroupForDate(date)) {
                GROUP_FUTURE -> listFuture.add(mapToRemoteOrderIdentifier(it))
                GROUP_TODAY -> listToday.add(mapToRemoteOrderIdentifier(it))
                GROUP_YESTERDAY -> listYesterday.add(mapToRemoteOrderIdentifier(it))
                GROUP_OLDER_TWO_DAYS -> listTwoDays.add(mapToRemoteOrderIdentifier(it))
                GROUP_OLDER_WEEK -> listWeek.add(mapToRemoteOrderIdentifier(it))
                GROUP_OLDER_MONTH -> listMonth.add(mapToRemoteOrderIdentifier(it))
            }
        }

        val allItems = mutableListOf<OrderListItemIdentifier>()
        if (listFuture.isNotEmpty()) {
            allItems += listOf(SectionHeaderIdentifier(GROUP_FUTURE)) + listFuture
        }

        if (listToday.isNotEmpty()) {
            allItems += listOf(SectionHeaderIdentifier(GROUP_TODAY)) + listToday
        }
        if (listYesterday.isNotEmpty()) {
            allItems += listOf(SectionHeaderIdentifier(GROUP_YESTERDAY)) + listYesterday
        }
        if (listTwoDays.isNotEmpty()) {
            allItems += listOf(SectionHeaderIdentifier(GROUP_OLDER_TWO_DAYS)) + listTwoDays
        }
        if (listWeek.isNotEmpty()) {
            allItems += listOf(SectionHeaderIdentifier(GROUP_OLDER_WEEK)) + listWeek
        }
        if (listMonth.isNotEmpty()) {
            allItems += listOf(SectionHeaderIdentifier(GROUP_OLDER_MONTH)) + listMonth
        }
        return allItems
    }

    override fun fetchList(listDescriptor: WCOrderListDescriptor, offset: Long) {
        val fetchOrderListPayload = FetchOrderListPayload(listDescriptor, offset)
        dispatcher.dispatch(WCOrderActionBuilder.newFetchOrderListAction(fetchOrderListPayload))
    }
}
