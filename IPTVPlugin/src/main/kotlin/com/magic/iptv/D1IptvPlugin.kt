package com.magic.iptv

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class D1IptvPlugin: Plugin() {
    override fun load(context: Context) {
        // This registers your provider when the app starts
        registerMainAPI(D1IptvProvider())
    }
}
