# Real-Time ECG Monitoring System

An IoT system for ECG signal acquisition, BLE transmission, and automated heart rate classification using AI.

## Features
* **Hardware:** ESP32 microcontroller + AD8232 ECG module.
* **App:** Android application (Kotlin) with real-time waveform display.
* **AI Model:** TensorFlow Lite model for basic arrhythmia detection.

## Structure
* `/app` - Android app source code and UI layouts.
* `/app/src/main/assets` - Embedded TensorFlow Lite (`.tflite`) model.

## Note
This is a university project developed for research and educational purposes.
