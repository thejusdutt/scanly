package com.scanly.gplay

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.scanly.platform.TipJar
import com.scanly.platform.TipState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Optional, one-time "tip" via Play Billing. This is a TIP, not a feature gate and never
 * a subscription — every feature works without it. Mirrors the research finding that
 * users will support free tools voluntarily but reject subscriptions on offline utilities.
 */
@Singleton
class BillingTipJar @Inject constructor(
    @ApplicationContext context: Context,
) : TipJar, PurchasesUpdatedListener {

    private val _state = MutableStateFlow<TipState>(TipState.Unavailable)
    override val state = _state.asStateFlow()

    private var productDetails: ProductDetails? = null

    private val client = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases()
        .build()

    init { connect() }

    private fun connect() {
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingServiceDisconnected() { /* retried on next launchTip */ }
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) queryProduct()
            }
        })
    }

    private fun queryProduct() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(TIP_PRODUCT_ID)
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build(),
            )).build()
        client.queryProductDetailsAsync(params) { result, list ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                productDetails = list.firstOrNull()
                val price = productDetails
                    ?.oneTimePurchaseOfferDetails?.formattedPrice
                if (price != null) _state.value = TipState.Available(price)
            }
        }
    }

    override fun launchTip(activity: Activity) {
        val details = productDetails ?: return
        val params = com.android.billingclient.api.BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(
                com.android.billingclient.api.BillingFlowParams.ProductDetailsParams
                    .newBuilder().setProductDetails(details).build(),
            )).build()
        client.launchBillingFlow(activity, params)
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        if (result.responseCode != BillingClient.BillingResponseCode.OK) return
        purchases?.forEach { purchase ->
            if (!purchase.isAcknowledged) {
                client.acknowledgePurchase(
                    AcknowledgePurchaseParams.newBuilder()
                        .setPurchaseToken(purchase.purchaseToken).build(),
                ) { /* ack done */ }
            }
            _state.value = TipState.Tipped
        }
    }

    private companion object {
        const val TIP_PRODUCT_ID = "tip_jar_once"
    }
}
