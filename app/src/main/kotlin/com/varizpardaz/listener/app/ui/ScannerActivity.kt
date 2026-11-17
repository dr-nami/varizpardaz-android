package com.varizpardaz.listener.app.ui

import com.journeyapps.barcodescanner.CaptureActivity

/**
 * The library's scanner activity, subclassed only so the manifest can declare it with the app's theme and
 * without the library's landscape-only orientation.
 */
class ScannerActivity : CaptureActivity()
