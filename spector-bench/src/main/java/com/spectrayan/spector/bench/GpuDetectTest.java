package com.spectrayan.spector.bench;

import com.spectrayan.spector.gpu.GpuCapability;

public class GpuDetectTest {
    public static void main(String[] args) {
        System.out.println(GpuCapability.detect().report());
        System.out.println("Available: " + GpuCapability.isAvailable());
    }
}
