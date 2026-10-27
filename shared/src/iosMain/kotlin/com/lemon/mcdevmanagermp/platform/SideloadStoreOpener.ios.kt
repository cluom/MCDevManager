package com.lemon.mcdevmanagermp.platform

import com.lemon.mcdevmanagermp.domain.update.AltStoreUpdate
import com.lemon.mcdevmanagermp.domain.update.SideloadStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import kotlin.coroutines.resume

actual suspend fun openSideloadStoreInstall(downloadUrl: String, store: SideloadStore): Boolean = withContext(Dispatchers.Main) {
    for (value in AltStoreUpdate.installUrls(downloadUrl, store)) {
        val url = NSURL.URLWithString(value) ?: continue
        val opened = suspendCancellableCoroutine<Boolean> { continuation ->
            UIApplication.sharedApplication.openURL(
                url,
                options = emptyMap<Any?, Any>(),
                completionHandler = { success ->
                    if (continuation.isActive) continuation.resume(success)
                }
            )
        }
        if (opened) return@withContext true
    }
    false
}
