package com.lemon.mcdevmanagermp.platform

import com.lemon.mcdevmanagermp.domain.update.SideloadStore

actual suspend fun openSideloadStoreInstall(downloadUrl: String, store: SideloadStore): Boolean = false
