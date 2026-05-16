package org.russia.leninvpn.protocol.openvpn

import android.net.VpnService.Builder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import net.openvpn.ovpn3.ClientAPI_Config
import org.russia.leninvpn.protocol.BadConfigException
import org.russia.leninvpn.protocol.Protocol
import org.russia.leninvpn.protocol.ProtocolState.DISCONNECTED
import org.russia.leninvpn.protocol.Statistics
import org.russia.leninvpn.protocol.VpnStartException
import org.russia.leninvpn.util.LibraryLoader.loadSharedLibrary
import org.russia.leninvpn.util.net.InetNetwork
import org.russia.leninvpn.util.net.getLocalNetworks
import org.russia.leninvpn.util.net.parseInetAddress
import org.json.JSONObject

open class OpenVpn : Protocol() {

    private var openVpnClient: OpenVpnClient? = null
    private lateinit var scope: CoroutineScope

    override val statistics: Statistics
        get() {
            openVpnClient?.let { client ->
                val stats = client.transport_stats()
                return Statistics.build {
                    setRxBytes(stats.bytesIn)
                    setTxBytes(stats.bytesOut)
                }
            }
            return Statistics.EMPTY_STATISTICS
        }

    override fun internalInit() {
        if (!isInitialized) {
            loadSharedLibrary(context, "ovpn3")
            loadSharedLibrary(context, "ovpnutil")
        }
        if (this::scope.isInitialized) {
            scope.cancel()
        }
        scope = CoroutineScope(Dispatchers.IO)
    }

    override suspend fun startVpn(config: JSONObject, vpnBuilder: Builder, protect: (Int) -> Boolean) {
        val configBuilder = OpenVpnConfig.Builder()

        openVpnClient = OpenVpnClient(
            configBuilder = configBuilder,
            state = state,
            getLocalNetworks = { ipv6 -> getLocalNetworks(context, ipv6) },
            establish = makeEstablish(vpnBuilder),
            protect = protect,
            onError = onError
        )

        try {
            openVpnClient?.let { client ->
                val openVpnConfig = parseConfig(config)
                val evalConfig = client.eval_config(openVpnConfig)
                if (evalConfig.error) {
                    throw BadConfigException("OpenVPN config parse error: ${evalConfig.message}")
                }

                // exclude remote server ip from vpn routes
                val remoteServer = config.getString("hostName")
                val remoteServerAddress = InetNetwork(parseInetAddress(remoteServer))
                configBuilder.excludeRoute(remoteServerAddress)

                configPluggableTransport(configBuilder, config)
                configBuilder.configSplitTunneling(config)
                configBuilder.configAppSplitTunneling(config)

                scope.launch {
                    val status = client.connect()
                    if (status.error) {
                        state.value = DISCONNECTED
                        onError("OpenVpn connect() error: ${status.status}: ${status.message}")
                    }
                }
            }
        } catch (e: Exception) {
            openVpnClient = null
            throw e
        }
    }

    override fun stopVpn() {
        openVpnClient?.stop()
        openVpnClient = null
    }

    override fun reconnectVpn(vpnBuilder: Builder, protect: (Int) -> Boolean) {
        openVpnClient?.let {
            it.establish = makeEstablish(vpnBuilder)
            it.reconnect(0)
        }
    }

    protected open fun parseConfig(config: JSONObject): ClientAPI_Config {
        val openVpnConfig = ClientAPI_Config()
        openVpnConfig.content = config.getJSONObject("openvpn_config_data").getString("config")
        return openVpnConfig
    }

    protected open fun configPluggableTransport(configBuilder: OpenVpnConfig.Builder, config: JSONObject) {}

    private fun makeEstablish(vpnBuilder: Builder): (OpenVpnConfig.Builder) -> Int = { configBuilder ->
        val openVpnConfig = configBuilder.build()
        buildVpnInterface(openVpnConfig, vpnBuilder)

        vpnBuilder.establish().use { tunFd ->
            if (tunFd == null) {
                throw VpnStartException("Create VPN interface: permission not granted or revoked")
            }
            return@use tunFd.detachFd()
        }
    }
}
