package io.legado.app.work

import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.work.Constraints
import androidx.work.NetworkType

/** WebDAV may be reachable on a LAN/VPN even when Android's public-internet probe fails. */
internal fun bookInfoRemoteConstraints(): Constraints = Constraints.Builder().setRequiredNetworkRequest(
    NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),
    NetworkType.CONNECTED,
).build()
