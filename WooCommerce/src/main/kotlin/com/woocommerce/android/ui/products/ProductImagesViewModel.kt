package com.woocommerce.android.ui.products

import android.content.DialogInterface
import android.net.Uri
import android.os.Parcelable
import com.squareup.inject.assisted.Assisted
import com.squareup.inject.assisted.AssistedInject
import com.woocommerce.android.R.string
import com.woocommerce.android.RequestCodes
import com.woocommerce.android.analytics.AnalyticsTracker
import com.woocommerce.android.analytics.AnalyticsTracker.Stat
import com.woocommerce.android.analytics.AnalyticsTracker.Stat.PRODUCT_DETAIL_IMAGE_TAPPED
import com.woocommerce.android.analytics.AnalyticsTracker.Stat.PRODUCT_IMAGE_SETTINGS_ADD_IMAGES_BUTTON_TAPPED
import com.woocommerce.android.di.ViewModelAssistedFactory
import com.woocommerce.android.extensions.areSameImagesAs
import com.woocommerce.android.media.ProductImagesService
import com.woocommerce.android.media.ProductImagesService.Companion.OnProductImageUploaded
import com.woocommerce.android.media.ProductImagesService.Companion.OnProductImagesUpdateCompletedEvent
import com.woocommerce.android.media.ProductImagesService.Companion.OnProductImagesUpdateStartedEvent
import com.woocommerce.android.media.ProductImagesServiceWrapper
import com.woocommerce.android.model.Product.Image
import com.woocommerce.android.model.toAppModel
import com.woocommerce.android.tools.NetworkStatus
import com.woocommerce.android.util.CoroutineDispatchers
import com.woocommerce.android.viewmodel.LiveDataDelegate
import com.woocommerce.android.viewmodel.MultiLiveEvent.Event
import com.woocommerce.android.viewmodel.MultiLiveEvent.Event.ExitWithResult
import com.woocommerce.android.viewmodel.MultiLiveEvent.Event.ShowDiscardDialog
import com.woocommerce.android.viewmodel.MultiLiveEvent.Event.ShowSnackbar
import com.woocommerce.android.viewmodel.SavedStateWithArgs
import com.woocommerce.android.viewmodel.ScopedViewModel
import kotlinx.android.parcel.Parcelize
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class ProductImagesViewModel @AssistedInject constructor(
    private val networkStatus: NetworkStatus,
    private val productImagesServiceWrapper: ProductImagesServiceWrapper,
    @Assisted savedState: SavedStateWithArgs,
    dispatchers: CoroutineDispatchers
) : ScopedViewModel(savedState, dispatchers) {
    private val navArgs: ProductImagesFragmentArgs by savedState.navArgs()

    val viewStateData = LiveDataDelegate(
        savedState,
        ViewState(
            isDoneButtonVisible = false,
            uploadingImageUris = ProductImagesService.getUploadingImageUris(navArgs.remoteId),
            isImageDeletingAllowed = navArgs.requestCode == RequestCodes.PRODUCT_DETAIL_IMAGES,
            images = navArgs.images.toList()
        )
    ) { old, new ->
        if (old != new) {
            updateDoneButtonVisibility()
        }
    }
    private var viewState by viewStateData

    private val originalImages = navArgs.images.toList()

    val images
        get() = viewState.images ?: emptyList()

    val isImageDeletingAllowed
        get() = viewState.isImageDeletingAllowed ?: true

    private val hasChanges: Boolean
        get() = !originalImages.areSameImagesAs(images) || !viewState.uploadingImageUris.isNullOrEmpty()

    init {
        EventBus.getDefault().register(this)

        if (navArgs.showChooser) {
            triggerEvent(ShowImageSourceDialog)
        } else {
            navArgs.selectedImage?.let {
                triggerEvent(ShowImageDetail(it, true))
            }
        }
    }

    fun uploadProductImages(remoteProductId: Long, localUriList: ArrayList<Uri>) {
        if (!networkStatus.isConnected()) {
            triggerEvent(ShowSnackbar(string.network_activity_no_connectivity))
            return
        }
        if (ProductImagesService.isBusy()) {
            triggerEvent(ShowSnackbar(string.product_image_service_busy))
            return
        }
        productImagesServiceWrapper.uploadProductMedia(remoteProductId, localUriList)
    }

    fun onShowStorageChooserButtonClicked() {
        AnalyticsTracker.track(
            Stat.PRODUCT_IMAGE_SETTINGS_ADD_IMAGES_SOURCE_TAPPED,
            mapOf(AnalyticsTracker.KEY_IMAGE_SOURCE to AnalyticsTracker.IMAGE_SOURCE_DEVICE)
        )
        triggerEvent(ShowStorageChooser)
    }

    fun onShowCameraButtonClicked() {
        AnalyticsTracker.track(
            Stat.PRODUCT_IMAGE_SETTINGS_ADD_IMAGES_SOURCE_TAPPED,
            mapOf(AnalyticsTracker.KEY_IMAGE_SOURCE to AnalyticsTracker.IMAGE_SOURCE_CAMERA)
        )
        triggerEvent(ShowCamera)
    }

    fun onShowWPMediaPickerButtonClicked() {
        AnalyticsTracker.track(
            Stat.PRODUCT_IMAGE_SETTINGS_ADD_IMAGES_SOURCE_TAPPED,
            mapOf(AnalyticsTracker.KEY_IMAGE_SOURCE to AnalyticsTracker.IMAGE_SOURCE_WPMEDIA)
        )
        triggerEvent(ShowWPMediaPicker)
    }

    fun onImageRemoved(imageId: Long) {
        viewState = viewState.copy(images = images.filter { it.id != imageId })
    }

    fun onImageSourceButtonClicked() {
        AnalyticsTracker.track(PRODUCT_IMAGE_SETTINGS_ADD_IMAGES_BUTTON_TAPPED)
        triggerEvent(ShowImageSourceDialog)
    }

    fun onGalleryImageClicked(image: Image) {
        AnalyticsTracker.track(PRODUCT_DETAIL_IMAGE_TAPPED)
        triggerEvent(ShowImageDetail(image))
    }

    fun onDoneButtonClicked() {
        AnalyticsTracker.track(
            Stat.PRODUCT_IMAGE_SETTINGS_DONE_BUTTON_TAPPED,
            mapOf(AnalyticsTracker.KEY_HAS_CHANGED_DATA to true)
        )

        triggerEvent(ExitWithResult(images))
    }

    fun onExit() {
        when {
            ProductImagesService.isUploadingForProduct(navArgs.remoteId) -> {
                triggerEvent(ShowDiscardDialog(
                    messageId = string.images_still_uploading_message,
                    positiveBtnAction = DialogInterface.OnClickListener { _, _ ->
                        ProductImagesService.cancel()
                        triggerEvent(ExitWithResult(originalImages))
                    }
                ))
            }
            hasChanges -> {
                triggerEvent(ShowDiscardDialog(
                    positiveBtnAction = DialogInterface.OnClickListener { _, _ ->
                        triggerEvent(ExitWithResult(originalImages))
                    }
                ))
            }
            else -> {
                triggerEvent(ExitWithResult(originalImages))
            }
        }
    }

    private fun updateDoneButtonVisibility() {
        viewState = viewState.copy(isDoneButtonVisible = hasChanges)
    }

    /**
     * The list of product images has started uploading
     */
    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onEventMainThread(event: OnProductImagesUpdateStartedEvent) {
        checkImageUploads(event.id)
    }

    /**
     * The list of product images has finished uploading
     */
    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onEventMainThread(event: OnProductImagesUpdateCompletedEvent) {
        if (event.isCancelled) {
            viewState = viewState.copy(uploadingImageUris = emptyList())
        } else {
            checkImageUploads(event.id)
        }
    }

    /**
     * Checks whether product images are uploading and ensures the view state reflects any currently
     * uploading images
     */
    private fun checkImageUploads(remoteProductId: Long) {
        viewState = if (ProductImagesService.isUploadingForProduct(remoteProductId)) {
            val uris = ProductImagesService.getUploadingImageUris(remoteProductId)
            viewState.copy(uploadingImageUris = uris)
        } else {
            viewState.copy(uploadingImageUris = emptyList())
        }
    }

    override fun onCleared() {
        super.onCleared()
        EventBus.getDefault().unregister(this)
    }

    /**
     * A single product image has finished uploading
     */
    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onEventMainThread(event: OnProductImageUploaded) {
        if (event.isError) {
            triggerEvent(ShowSnackbar(string.product_image_service_error_uploading))
        } else {
            event.media?.let { media ->
                viewState = viewState.copy(images = images + media.toAppModel())
            }
        }
        checkImageUploads(navArgs.remoteId)
    }

    @Parcelize
    data class ViewState(
        val uploadingImageUris: List<Uri>? = null,
        val isDoneButtonVisible: Boolean? = null,
        val isImageDeletingAllowed: Boolean? = null,
        val images: List<Image>? = null
    ) : Parcelable

    object ShowImageSourceDialog : Event()
    object ShowStorageChooser : Event()
    object ShowCamera : Event()
    object ShowWPMediaPicker : Event()
    data class ShowImageDetail(val image: Image, val isOpenedDirectly: Boolean = false) : Event()

    @AssistedInject.Factory
    interface Factory : ViewModelAssistedFactory<ProductImagesViewModel>
}
