package org.amnezia.leninvpn

import org.amnezia.leninvpn.protocol.Protocol
import org.amnezia.leninvpn.protocol.awg.Awg
import org.amnezia.leninvpn.protocol.cloak.Cloak
import org.amnezia.leninvpn.protocol.openvpn.OpenVpn
import org.amnezia.leninvpn.protocol.wireguard.Wireguard
import org.amnezia.leninvpn.protocol.xray.Xray

// Must stay in sync with android.defaultConfig.applicationId
private const val APPLICATION_ID = "org.amnezia.LeninVPN"

enum class VpnProto(
    val label: String,
    val processName: String,
    val serviceClass: Class<out LeninVpnService>
) {
    WIREGUARD(
        "WireGuard",
        "${APPLICATION_ID}:leninvpnAwgService",
        AwgService::class.java
    ) {
        override fun createProtocol(): Protocol = Wireguard()
    },

    AWG(
        "AmneziaWG",
        "${APPLICATION_ID}:leninvpnAwgService",
        AwgService::class.java
    ) {
        override fun createProtocol(): Protocol = Awg()
    },

    OPENVPN(
        "OpenVPN",
        "${APPLICATION_ID}:leninvpnOpenVpnService",
        OpenVpnService::class.java
    ) {
        override fun createProtocol(): Protocol = OpenVpn()
    },

    CLOAK(
        "Cloak",
        "${APPLICATION_ID}:leninvpnOpenVpnService",
        OpenVpnService::class.java
    ) {
        override fun createProtocol(): Protocol = Cloak()
    },

    XRAY(
        "XRay",
        "${APPLICATION_ID}:leninvpnXrayService",
        XrayService::class.java
    ) {
        override fun createProtocol(): Protocol = Xray.instance
    },

    SSXRAY(
        "SSXRay",
        "${APPLICATION_ID}:leninvpnXrayService",
        XrayService::class.java
    ) {
        override fun createProtocol(): Protocol = Xray.instance
    };

    private var _protocol: Protocol? = null
    val protocol: Protocol
        get() {
            if (_protocol == null) _protocol = createProtocol()
            return _protocol ?: throw AssertionError("Set to null by another thread")
        }

    protected abstract fun createProtocol(): Protocol

    companion object {
        fun get(protocolName: String): VpnProto = VpnProto.valueOf(protocolName.uppercase())
    }
}