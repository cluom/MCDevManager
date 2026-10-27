package com.lemon.mcdevmanagermp.platform

/** True means iOS handed the URL to an app, not that signing or installation succeeded. */
expect suspend fun openAltStoreInstall(downloadUrl: String): Boolean
