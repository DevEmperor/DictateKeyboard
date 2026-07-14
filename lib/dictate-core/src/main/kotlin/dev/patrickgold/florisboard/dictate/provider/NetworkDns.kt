/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.provider

import okhttp3.Dns
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Keeps the resolver's ordering within each address family, but tries IPv4 before IPv6. Some Android
 * networks advertise IPv6 while silently dropping IPv6 traffic; OkHttp 4 otherwise waits for each dead
 * route before trying the reachable IPv4 address. IPv6-only hosts still work because no addresses are
 * removed.
 */
internal object Ipv4FirstDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        prioritizeIpv4(Dns.SYSTEM.lookup(hostname))

    internal fun prioritizeIpv4(addresses: List<InetAddress>): List<InetAddress> =
        addresses.sortedBy { if (it is Inet4Address) 0 else 1 }
}

/** A dead route should fail quickly while the enclosing request keeps its independent overall budget. */
internal const val NETWORK_CONNECT_TIMEOUT_SECONDS = 8L
