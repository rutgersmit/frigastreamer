package nl.frigastreamer.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nl.frigastreamer.util.Logger

data class NetworkStatus(val available: Boolean, val description: String)

interface NetworkMonitor {
    val status: StateFlow<NetworkStatus>
}

/**
 * Netwerkdetectie via [ConnectivityManager]-callbacks. Bewust ZONDER de eis
 * NET_CAPABILITY_INTERNET/VALIDATED: een LAN zonder internet is voor Frigate prima.
 */
class AndroidNetworkMonitor(context: Context, private val log: Logger) : NetworkMonitor {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val networks = LinkedHashMap<Network, String>()
    private val _status = MutableStateFlow(NetworkStatus(available = true, description = "onbekend"))
    override val status: StateFlow<NetworkStatus> = _status.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            synchronized(networks) { networks[network] = describe(network) }
            update("available")
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            synchronized(networks) { if (network in networks) networks[network] = describe(caps) }
            update(null)
        }

        override fun onLost(network: Network) {
            synchronized(networks) { networks.remove(network) }
            update("lost")
        }
    }

    init {
        val request = NetworkRequest.Builder()
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .build()
        // Begintoestand: is er nu een actief netwerk?
        val active = cm.activeNetwork
        _status.value = NetworkStatus(active != null, if (active != null) describe(active) else "geen netwerk")
        cm.registerNetworkCallback(request, callback)
    }

    private fun update(event: String?) {
        val snapshot = synchronized(networks) { networks.values.toList() }
        val new = NetworkStatus(snapshot.isNotEmpty(), if (snapshot.isEmpty()) "geen netwerk" else snapshot.joinToString(", "))
        if (new != _status.value) {
            if (new.available != _status.value.available || event != null) {
                log.i(TAG, "event=network_${event ?: "changed"} available=${new.available} networks=\"${new.description}\"")
            }
            _status.value = new
        }
    }

    private fun describe(network: Network): String = cm.getNetworkCapabilities(network)?.let { describe(it) } ?: "netwerk"

    private fun describe(caps: NetworkCapabilities): String = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobiel"
        else -> "netwerk"
    }

    companion object {
        private const val TAG = "FS.Net"
    }
}
