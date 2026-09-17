package com.aeonos.portalha

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.sendspin.protocol.NsdBrowser
import com.sendspin.protocol.NsdRegistrar
import com.sendspin.protocol.NsdServiceEvent
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android mDNS for the Sendspin client. The protocol library deliberately leaves discovery to
 * the platform ([NsdBrowser] / [NsdRegistrar]); here we back both with `NsdManager`.
 */
private const val TAG = "PortalHA"

class AndroidNsdBrowser(context: Context) : NsdBrowser {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    override fun browse(serviceType: String): Flow<NsdServiceEvent> = callbackFlow {
        // NsdManager.resolveService can only have one resolve in flight at a time on older
        // releases ("listener already in use"), so resolves are queued and run one at a time.
        val pending = ConcurrentLinkedQueue<NsdServiceInfo>()
        val resolving = AtomicBoolean(false)

        fun pumpResolves() {
            if (!resolving.compareAndSet(false, true)) return
            val info = pending.poll()
            if (info == null) { resolving.set(false); return }
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                private fun next() { resolving.set(false); pumpResolves() }
                override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                    Log.i(TAG, "sendspin: nsd resolve failed '${si.serviceName}' err=$errorCode")
                    next()
                }
                override fun onServiceResolved(si: NsdServiceInfo) {
                    val host = si.host?.hostAddress
                    if (host != null) {
                        trySend(NsdServiceEvent.ServiceResolved(si.serviceName, host, si.port))
                    }
                    next()
                }
            })
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) { Log.i(TAG, "sendspin: nsd browsing $t") }
            override fun onServiceFound(si: NsdServiceInfo) {
                trySend(NsdServiceEvent.ServiceFound(si.serviceName))
                pending.add(si); pumpResolves()
            }
            override fun onServiceLost(si: NsdServiceInfo) {
                trySend(NsdServiceEvent.ServiceLost(si.serviceName))
            }
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, errorCode: Int) {
                trySend(NsdServiceEvent.BrowseError(errorCode))
            }
            override fun onStopDiscoveryFailed(t: String, errorCode: Int) {}
        }

        runCatching { nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { trySend(NsdServiceEvent.BrowseError(-1)) }

        awaitClose { runCatching { nsd.stopServiceDiscovery(listener) } }
    }
}

class AndroidNsdRegistrar(context: Context) : NsdRegistrar {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    override fun register(
        serviceName: String,
        serviceType: String,
        port: Int,
        attributes: Map<String, String>,
    ): Flow<Unit> = callbackFlow {
        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            this.serviceType = serviceType
            this.port = port
            for ((k, v) in attributes) setAttribute(k, v)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(si: NsdServiceInfo) {
                Log.i(TAG, "sendspin: nsd registered '${si.serviceName}' on $port")
                trySend(Unit)
            }
            override fun onRegistrationFailed(si: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "sendspin: nsd registration failed err=$errorCode")
                close()
            }
            override fun onServiceUnregistered(si: NsdServiceInfo) {}
            override fun onUnregistrationFailed(si: NsdServiceInfo, errorCode: Int) {}
        }
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { close() }
        awaitClose { runCatching { nsd.unregisterService(listener) } }
    }
}
