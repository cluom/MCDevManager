package com.lemon.mcdevmanagermp.platform

import com.lemon.mcdevmanagermp.domain.update.SideloadStore

/** True means iOS handed the URL to an app, not that signing or installation succeeded. */
expect suspend fun openSideloadStoreInstall(downloadUrl: String, store: SideloadStore): Boolean
