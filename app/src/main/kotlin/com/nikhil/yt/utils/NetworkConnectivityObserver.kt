/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



package com.nikhil.yt.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Simple NetworkConnectivityObserver based on OuterTune's implementation
 * Provides network connectivity monitoring for auto-play functionality
 */
class NetworkConnectivityObserver(context: Context) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val initialNetwork = connectivityManager.activeNetwork
    private val _networkStatus =
        MutableStateFlow(isValidatedInternetNetwork(initialNetwork))
    val networkStatus: StateFlow<Boolean> = _networkStatus.asStateFlow()

    /*
     * A VPN hand-off can keep INTERNET available while changing the default
     * Android Network (and therefore the public exit IP). A Boolean online /
     * offline signal cannot represent that transition, so playback also gets
     * the stable network handle and may discard only network-bound state.
     */
    private val _activeNetworkId =
        MutableStateFlow(initialNetwork?.networkHandle)
    val activeNetworkId: StateFlow<Long?> = _activeNetworkId.asStateFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            publish(network)
        }

        override fun onLost(network: Network) {
            if (_activeNetworkId.value == network.networkHandle) {
                publish(connectivityManager.activeNetwork)
            }
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) {
            if (_activeNetworkId.value == network.networkHandle) {
                publish(network, networkCapabilities)
            }
        }
    }

    init {
        try {
            /*
             * Observe the default route, not every network that happens to
             * exist. With registerNetworkCallback an old Wi-Fi/VPN onLost
             * event could incorrectly mark the new active VPN as offline.
             */
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
        } catch (_: Exception) {
            _networkStatus.value = isCurrentlyConnected()
        }
    }

    fun unregister() {
        runCatching {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        }
    }

    /**
     * Check current connectivity state synchronously
     */
    fun isCurrentlyConnected(): Boolean =
        isValidatedInternetNetwork(connectivityManager.activeNetwork)

    private fun publish(
        network: Network?,
        capabilities: NetworkCapabilities? =
            network?.let { connectivityManager.getNetworkCapabilities(it) },
    ) {
        _activeNetworkId.value = network?.networkHandle
        _networkStatus.value = isValidatedInternetNetwork(capabilities)
    }

    private fun isValidatedInternetNetwork(network: Network?): Boolean =
        isValidatedInternetNetwork(
            network?.let { connectivityManager.getNetworkCapabilities(it) },
        )

    private fun isValidatedInternetNetwork(
        capabilities: NetworkCapabilities?,
    ): Boolean =
        capabilities?.let {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } == true
}
