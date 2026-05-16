package org.russia.leninvpn

import org.russia.leninvpn.protocol.Protocol
import org.russia.leninvpn.protocol.awg.Awg
import org.russia.leninvpn.protocol.cloak.Cloak
import org.russia.leninvpn.protocol.openvpn.OpenVpn
import org.russia.leninvpn.protocol.wireguard.Wireguard
import org.russia.leninvpn.protocol.xray.Xray

// Must stay in sync with android.defaultConfig.applicationId
private const val APPLICATION_ID = "org.russia.LeninVPN"

enum class VpnProto(
    val label: String,
    val processName: String,
    val serviceClass: Class<out AmneziaVpnService>
) {
    WIREGUARD(
        "WireGuard",
        "${APPLICATION_ID}:amneziaAwgService",
        AwgService::class.java
    ) {
        override fun createProtocol(): Protocol = Wireguard()
    },

    AWG(
        "AmneziaWG",
        "${APPLICATION_ID}:amneziaAwgService",
        AwgService::class.java
    ) {
        override fun createProtocol(): Protocol = Awg()
    },

    OPENVPN(
        "OpenVPN",
        "${APPLICATION_ID}:amneziaOpenVpnService",
        OpenVpnService::class.java
    ) {
        override fun createProtocol(): Protocol = OpenVpn()
    },

    CLOAK(
        "Cloak",
        "${APPLICATION_ID}:amneziaOpenVpnService",
        OpenVpnService::class.java
    ) {
        override fun createProtocol(): Protocol = Cloak()
    },

    XRAY(
        "XRay",
        "${APPLICATION_ID}:amneziaXrayService",
        XrayService::class.java
    ) {
        override fun createProtocol(): Protocol = Xray.instance
    },

    SSXRAY(
        "SSXRay",
        "${APPLICATION_ID}:amneziaXrayService",
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