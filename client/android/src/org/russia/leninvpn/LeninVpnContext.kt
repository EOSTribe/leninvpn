package org.russia.leninvpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.RegisterReceiverFlags
import org.russia.leninvpn.protocol.ProtocolState
import org.russia.leninvpn.protocol.ProtocolState.CONNECTED
import org.russia.leninvpn.protocol.ProtocolState.CONNECTING
import org.russia.leninvpn.protocol.ProtocolState.DISCONNECTED
import org.russia.leninvpn.protocol.ProtocolState.DISCONNECTING
import org.russia.leninvpn.protocol.ProtocolState.RECONNECTING
import org.russia.leninvpn.protocol.ProtocolState.UNKNOWN

fun Context.getString(state: ProtocolState): String =
    getString(
        when (state) {
            DISCONNECTED, UNKNOWN -> R.string.disconnected
            CONNECTED -> R.string.connected
            CONNECTING -> R.string.connecting
            DISCONNECTING -> R.string.disconnecting
            RECONNECTING -> R.string.reconnecting
        }
    )

fun Context.registerBroadcastReceiver(
    action: String,
    @RegisterReceiverFlags flags: Int = ContextCompat.RECEIVER_EXPORTED,
    onReceive: (Intent?) -> Unit
): BroadcastReceiver = registerBroadcastReceiver(arrayOf(action), flags, onReceive)

fun Context.registerBroadcastReceiver(
    actions: Array<String>,
    @RegisterReceiverFlags flags: Int = ContextCompat.RECEIVER_EXPORTED,
    onReceive: (Intent?) -> Unit
): BroadcastReceiver =
    object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            onReceive(intent)
        }
    }.also {
        ContextCompat.registerReceiver(
            this,
            it,
            IntentFilter().apply {
                actions.forEach(::addAction)
            },
            flags
        )
    }

fun Context.unregisterBroadcastReceiver(receiver: BroadcastReceiver?) {
    receiver?.let { this.unregisterReceiver(it) }
}
