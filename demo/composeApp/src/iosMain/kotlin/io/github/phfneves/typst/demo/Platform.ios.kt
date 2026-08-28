package io.github.phfneves.typst.demo

import platform.UIKit.UIDevice

actual val platformLabel: String =
    UIDevice.currentDevice.systemName + " " + UIDevice.currentDevice.systemVersion
