#include <Arduino.h>
#include <math.h>

// ===== BLE (Arduino ESP32 BLE) =====
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// ===================== PINI =====================
static const int PIN_EKG = 36;   // VP / GPIO36 (ADC1_CH0)
static const int PIN_LOP = 26;   // LO+
static const int PIN_LON = 27;   // LO-

// ===================== PARAMETRI ESANTIONARE =====================
static const int FS = 250;
static const uint32_t TS_US = 1000000UL / FS;
static uint32_t tNext = 0;

// ===================== FILTRARE =====================
static float meanDC = 0.0f;
static const float alphaDC = 0.02f;
static float yLP = 0.0f;

// ===================== DETECTIE QRS (energie) =====================
static float prevHP = 0.0f;

static const int WIN = 38; // ~150ms la 250Hz
static float winBuf[WIN];
static int winIdx = 0;
static float winSum = 0.0f;

static float avgE = 0.0f;
static float thrFactor = 2.5f;      // tuning: 2.0..4.0
static uint32_t lastR_us = 0;
static uint32_t lastR_seen_us = 0;  // <-- IMPORTANT: pentru timeout
static int bpm = 0;
static const uint32_t REFRACT_US = 280000; // 280ms
static const uint32_t BPM_TIMEOUT_US = 2500000UL; // 2.5s fara R => bpm invalid

// ===================== BLE UUIDs =====================
static const char* DEVICE_NAME = "ESP32_EKG";
static const char* SERVICE_UUID      = "4fafc201-1fb5-459e-8fcc-c5c9c3319140";
static const char* CHAR_WAVE_UUID    = "4fafc201-1fb5-459e-8fcc-c5c9c3319141"; // notify
static const char* CHAR_BPM_UUID     = "4fafc201-1fb5-459e-8fcc-c5c9c3319142"; // notify
static const char* CHAR_STATUS_UUID  = "4fafc201-1fb5-459e-8fcc-c5c9c3319143"; // notify

BLEServer* pServer = nullptr;
BLECharacteristic* pCharWave = nullptr;
BLECharacteristic* pCharBpm = nullptr;
BLECharacteristic* pCharStatus = nullptr;


static bool deviceConnected = false;

// ===================== WAVE PACKET (20 bytes) =====================
// [seq:2][flags:1][n:1][8 samples int16:16] = 20 bytes
static uint16_t seqWave = 0;
static int16_t waveBuf8[8];
static uint8_t waveCount = 0;

static inline uint8_t makeFlags(bool leadOff) {
  return leadOff ? 0x01 : 0x00;
}

// ===================== RESET DETECTOR (CHEIE) =====================
static void resetDetector() {
  meanDC = 0.0f;
  yLP = 0.0f;
  prevHP = 0.0f;

  for (int i = 0; i < WIN; i++) winBuf[i] = 0.0f;
  winIdx = 0;
  winSum = 0.0f;

  avgE = 0.0f;
  lastR_us = 0;
  lastR_seen_us = 0;
  bpm = 0;
}

// ===================== BLE CALLBACKS =====================
class MyServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer* pServer) override {
    deviceConnected = true;
    Serial.println("[BLE] Dispozitiv conectat.");
  }
  void onDisconnect(BLEServer* pServer) override {
    deviceConnected = false;
    Serial.println("[BLE] Dispozitiv deconectat. Restart advertising...");
    BLEDevice::startAdvertising();
  }
};

static void bleInit() {
  BLEDevice::init(DEVICE_NAME);
  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new MyServerCallbacks());

  BLEService* pService = pServer->createService(SERVICE_UUID);

  pCharWave = pService->createCharacteristic(CHAR_WAVE_UUID, BLECharacteristic::PROPERTY_NOTIFY);
  pCharWave->addDescriptor(new BLE2902());

  pCharBpm = pService->createCharacteristic(CHAR_BPM_UUID, BLECharacteristic::PROPERTY_NOTIFY);
  pCharBpm->addDescriptor(new BLE2902());

  pCharStatus = pService->createCharacteristic(CHAR_STATUS_UUID, BLECharacteristic::PROPERTY_NOTIFY);
  pCharStatus->addDescriptor(new BLE2902());

  pService->start();

  BLEAdvertising* pAdvertising = BLEDevice::getAdvertising();
  pAdvertising->addServiceUUID(SERVICE_UUID);
  pAdvertising->setScanResponse(true);
  pAdvertising->start();

  Serial.println("[BLE] Advertising pornit. Cauta ESP32_EKG pe telefon.");
}

// ===================== NOTIFY BPM + STATUS (1Hz) =====================
static uint32_t lastBpmNotifyMs = 0;
static void notifyBpmAndStatus(bool leadOff) {
  if (!deviceConnected) return;

  uint32_t nowMs = millis();
  if (nowMs - lastBpmNotifyMs >= 1000) {
    lastBpmNotifyMs = nowMs;

    // bpm=0 inseamna "invalid / no HR"
    uint16_t bpm_u16 = (bpm < 0) ? 0 : (uint16_t)bpm;
    uint8_t bpmPayload[2] = {
      (uint8_t)(bpm_u16 & 0xFF),
      (uint8_t)((bpm_u16 >> 8) & 0xFF)
    };

    pCharBpm->setValue(bpmPayload, sizeof(bpmPayload));
    pCharBpm->notify();

    uint8_t st = leadOff ? 1 : 0;
    pCharStatus->setValue(&st, 1);
    pCharStatus->notify();

    Serial.print("[INFO] BPM=");
    Serial.print(bpm);
    Serial.print(" | LeadOff=");
    Serial.println(leadOff ? "1" : "0");
  }
}

// ===================== NOTIFY WAVE PACKET =====================
static void notifyWavePacket(bool leadOff) {
  if (!deviceConnected) return;
  if (waveCount < 8) return;

  uint8_t payload[20];
  payload[0] = (uint8_t)(seqWave & 0xFF);
  payload[1] = (uint8_t)((seqWave >> 8) & 0xFF);
  payload[2] = makeFlags(leadOff);
  payload[3] = 8;

  for (int i = 0; i < 8; i++) {
    int16_t s = waveBuf8[i];
    payload[4 + 2*i]     = (uint8_t)(s & 0xFF);
    payload[4 + 2*i + 1] = (uint8_t)((s >> 8) & 0xFF);
  }

  pCharWave->setValue(payload, sizeof(payload));
  pCharWave->notify();

  seqWave++;
  waveCount = 0;
}

// ===================== SETUP =====================
void setup() {
  Serial.begin(115200);
  delay(300);

  pinMode(PIN_LOP, INPUT);
  pinMode(PIN_LON, INPUT);

  analogReadResolution(12);
  analogSetPinAttenuation(PIN_EKG, ADC_11db);

  resetDetector();
  bleInit();

  tNext = micros();
}

// ===================== LOOP =====================
void loop() {
  uint32_t now = micros();

  if ((int32_t)(now - tNext) >= 0) {
    tNext += TS_US;

    // lead-off
    static bool prevLeadOff = true;
    bool leadOff = (digitalRead(PIN_LOP) == 1) || (digitalRead(PIN_LON) == 1);

    // DACA s-a schimbat lead-off (cand schimbi persoana se intampla des), reset total
    if (leadOff != prevLeadOff) {
      resetDetector();
      prevLeadOff = leadOff;
    }

    int raw = analogRead(PIN_EKG);

    // --- Filtrare ---
    meanDC = (1.0f - alphaDC) * meanDC + alphaDC * (float)raw;
    float hp = (float)raw - meanDC;
    yLP = 0.85f * yLP + 0.15f * hp;

    // --- Energie QRS ---
    float d = hp - prevHP;
    prevHP = hp;
    float e = d * d;

    winSum -= winBuf[winIdx];
    winBuf[winIdx] = e;
    winSum += e;
    winIdx = (winIdx + 1) % WIN;

    float integ = winSum / (float)WIN;

    // prag adaptiv (doar cand ai electrozi)
    if (!leadOff) avgE = 0.995f * avgE + 0.005f * integ;
    else avgE *= 0.98f;

    float thr = thrFactor * avgE;

    // OPTIONAL: clamp prag (ajuta la cazuri cu zgomot mare)
    // thr = constrain(thr, 5.0f, 200000.0f);

    // detectie R
    bool Rdet = false;
    if (!leadOff && integ > thr && (now - lastR_us) > REFRACT_US) {
      Rdet = true;

      uint32_t rr = (lastR_us == 0) ? 0 : (now - lastR_us);
      lastR_us = now;
      lastR_seen_us = now;

      if (rr > 300000 && rr < 2000000) { // 30..200 bpm
        bpm = (int)(60000000.0f / (float)rr);
      }
    }

    // DACA nu ai R de mult timp => bpm invalid (NU mai “ramane” valoarea veche)
    if (leadOff) {
      bpm = 0;
    } else {
      if (lastR_seen_us != 0 && (now - lastR_seen_us) > BPM_TIMEOUT_US) {
        bpm = 0;
      }
    }

    // --- Waveform BLE (trimitem yLP) ---
    int16_t sample = (int16_t) constrain((int)(yLP), -32768, 32767);
    waveBuf8[waveCount++] = sample;

    // --- Serial Plotter ---
    Serial.print(raw);
    Serial.print(" ");
    Serial.print((int)(yLP + 2000));
    Serial.print(" ");
    Serial.print(Rdet ? 3500 : 0);
    Serial.print(" ");
    Serial.println(leadOff ? 2000 : 0);

    // BLE notify
    notifyWavePacket(leadOff);
    notifyBpmAndStatus(leadOff);
  }
}
