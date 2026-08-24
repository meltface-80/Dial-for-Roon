package com.roondial

import android.app.Application
import com.roondial.roon.RoonClient

/**
 * Holds the single Roon connection. The activity and the media session service
 * are two views onto the same client — a second connection would show up as a
 * second extension in Roon's settings.
 */
class RoonApp : Application() {
    val roon: RoonClient by lazy { RoonClient(this) }
}
