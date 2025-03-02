package com.sl1mslav.blescanner

import androidx.lifecycle.ViewModel
import com.sl1mslav.blescanner.bleAvailability.BleAvailabilityObserver

class MainActivityViewModel(
    availabilityTracker: BleAvailabilityObserver
): ViewModel() {
    val flow = availabilityTracker.bleAvailability
}