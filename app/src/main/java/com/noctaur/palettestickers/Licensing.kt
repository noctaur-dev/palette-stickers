package com.noctaur.palettestickers

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import com.samsung.android.sdk.iap.lib.constants.HelperDefine
import com.samsung.android.sdk.iap.lib.helper.IapHelper
import com.samsung.android.sdk.iap.lib.listener.OnAcknowledgePurchasesListener
import com.samsung.android.sdk.iap.lib.listener.OnGetOwnedListListener
import com.samsung.android.sdk.iap.lib.listener.OnPaymentListener
import com.samsung.android.sdk.iap.lib.vo.AcknowledgeVo
import com.samsung.android.sdk.iap.lib.vo.ErrorVo
import com.samsung.android.sdk.iap.lib.vo.OwnedProductVo
import com.samsung.android.sdk.iap.lib.vo.PurchaseVo

/**
 * Free: [FREE_PACKS] packs. Peelbox Unlimited (one-time Samsung IAP item) removes the limit.
 * The dev build is always unlimited. The unlock is cached locally; "Restore" re-checks it.
 */
object Licensing {
    const val FREE_PACKS = 3
    const val ITEM_UNLIMITED = "peelbox_unlimited"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("licensing", Context.MODE_PRIVATE)

    fun isUnlocked(ctx: Context): Boolean = !BuildConfig.STORE || prefs(ctx).getBoolean("unlimited", false)

    private fun setUnlocked(ctx: Context) = prefs(ctx).edit().putBoolean("unlimited", true).apply()

    fun canCreatePack(ctx: Context): Boolean = isUnlocked(ctx) || Library.all().size < FREE_PACKS

    /** Importing into a pack you already have is always fine; a new pack counts toward the limit. */
    fun canImport(ctx: Context, packId: String): Boolean = Library.get(packId) != null || canCreatePack(ctx)

    /** Runs [onAllowed] if a new pack may be created; otherwise offers the unlock. */
    fun requestNewPack(a: Activity, onDenied: (() -> Unit)? = null, onAllowed: () -> Unit) {
        if (canCreatePack(a)) onAllowed() else offerUnlock(a, onDenied, onAllowed)
    }

    fun offerUnlock(a: Activity, onDenied: (() -> Unit)? = null, onUnlocked: (() -> Unit)? = null) {
        var acted = false
        AlertDialog.Builder(a)
            .setTitle("Peelbox Unlimited")
            .setMessage(
                "The free version holds $FREE_PACKS packs. Unlock unlimited packs with a one-time " +
                    "purchase — no subscription, no account, yours forever.",
            )
            .setPositiveButton("Unlock") { _, _ ->
                acted = true
                purchase(a) { ok -> if (ok) onUnlocked?.invoke() else onDenied?.invoke() }
            }
            .setNeutralButton("Restore purchase") { _, _ ->
                acted = true
                restore(a) { ok -> if (ok) onUnlocked?.invoke() else onDenied?.invoke() }
            }
            .setNegativeButton("Not now", null)
            .setOnDismissListener { if (!acted) onDenied?.invoke() }
            .show()
    }

    private fun helper(a: Activity): IapHelper = IapHelper.getInstance(a.applicationContext).apply {
        setOperationMode(
            if (BuildConfig.IAP_TEST) HelperDefine.OperationMode.OPERATION_MODE_TEST
            else HelperDefine.OperationMode.OPERATION_MODE_PRODUCTION,
        )
    }

    fun purchase(a: Activity, done: (Boolean) -> Unit) {
        val started = try {
            helper(a).startPayment(ITEM_UNLIMITED, "", "", OnPaymentListener { error: ErrorVo, purchase: PurchaseVo? ->
                when (error.errorCode) {
                    IapHelper.IAP_ERROR_NONE -> {
                        setUnlocked(a)
                        purchase?.purchaseId?.let { acknowledge(a, it) }
                        Dialogs.toast(a, "Unlocked — thank you!")
                        done(true)
                    }
                    IapHelper.IAP_ERROR_ALREADY_PURCHASED -> restore(a, done)
                    IapHelper.IAP_PAYMENT_IS_CANCELED -> done(false)
                    else -> {
                        Dialogs.toast(a, "Purchase didn't go through: ${error.errorString}")
                        done(false)
                    }
                }
            })
        } catch (e: Throwable) {
            false
        }
        if (!started) {
            Dialogs.toast(a, "Purchases need the Galaxy Store app on this phone")
            done(false)
        }
    }

    fun restore(a: Activity, done: (Boolean) -> Unit) {
        val started = try {
            helper(a).getOwnedList(IapHelper.PRODUCT_TYPE_ALL, OnGetOwnedListListener { error: ErrorVo, owned: ArrayList<OwnedProductVo>? ->
                val mine = owned.orEmpty().filter { it.itemId == ITEM_UNLIMITED }
                if (error.errorCode == IapHelper.IAP_ERROR_NONE && mine.isNotEmpty()) {
                    setUnlocked(a)
                    mine.mapNotNull { it.purchaseId }.forEach { acknowledge(a, it) }
                    Dialogs.toast(a, "Purchase restored")
                    done(true)
                } else {
                    Dialogs.toast(
                        a,
                        if (error.errorCode == IapHelper.IAP_ERROR_NONE) "No previous purchase found"
                        else "Couldn't check purchases: ${error.errorString}",
                    )
                    done(false)
                }
            })
        } catch (e: Throwable) {
            false
        }
        if (!started) {
            Dialogs.toast(a, "Purchases need the Galaxy Store app on this phone")
            done(false)
        }
    }

    /** Tells Samsung the user received the item (unacknowledged purchases may be refunded). */
    private fun acknowledge(a: Activity, purchaseId: String) {
        try {
            helper(a).acknowledgePurchases(purchaseId, OnAcknowledgePurchasesListener { _: ErrorVo, _: ArrayList<AcknowledgeVo>? -> })
        } catch (e: Throwable) {
            // Already acknowledged or Store unavailable — the unlock itself is unaffected.
        }
    }
}
