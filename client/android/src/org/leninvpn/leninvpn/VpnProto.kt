package org.leninvpn.leninvpn

import org.leninvpn.leninvpn.protocol.Protocol
import org.leninvpn.leninvpn.protocol.awg.Awg
import org.leninvpn.leninvpn.protocol.cloak.Cloak
import org.leninvpn.leninvpn.protocol.openvpn.OpenVpn
import org.leninvpn.leninvpn.protocol.wireguard.Wireguard
import org.leninvpn.leninvpn.protocol.xray.Xray

// Must stay in sync with android.defaultConfig.applicationId
private const val APPLICATION_ID = "org.leninvpn.LeninVPN"

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