/* Sweep
  by BARRAGAN <http://barraganstudio.com>
  This example code is in the public domain.

  modified 28 May 2015
  by Michael C. Miller
  modified 8 Nov 2013
  by Scott Fitzgerald

  http://arduino.cc/en/Tutorial/Sweep
*/


#include <WiFi.h>
#include <WebServer.h>
#include "esp_camera.h"

const char* hotspotName = "Esp-Cam-Hotspot";
const char* hotspotPassword = "25502550";
const bool serialOutputEnabled = false;
WebServer server(80);
WiFiServer cameraStreamServer(81);
WiFiClient cameraStreamClient;

const byte coilPins[] = {14, 15, 13, 12};
const byte coilCount = sizeof(coilPins) / sizeof(coilPins[0]);
const byte onboardLedPin = 0;
const unsigned int slowestStepDelayMs = 10;
const unsigned int fastestStepDelayMs = 2;
const unsigned int overdriveStepDelayMs = 1;
unsigned int stepDelayMs = 4;
int speedPercent = 75;
bool overdriveEnabled = false;
long currentPosition = 0;
long targetPosition = 0;
int sequenceIndex = 0;
unsigned long lastStepAt = 0;
String commandBuffer;
unsigned long lastFrameSentAt = 0;
unsigned long lastStreamStatsAt = 0;
const unsigned long frameIntervalMs = 100;

#define SERIAL_PRINT(value) do { if (serialOutputEnabled) Serial.print(value); } while (0)
#define SERIAL_PRINTLN(value) do { if (serialOutputEnabled) Serial.println(value); } while (0)
#define SERIAL_PRINTF(...) do { if (serialOutputEnabled) Serial.printf(__VA_ARGS__); } while (0)

bool initializeCamera() {
  camera_config_t config;
  config.ledc_channel = LEDC_CHANNEL_0;
  config.ledc_timer = LEDC_TIMER_0;
  config.pin_d0 = 5;
  config.pin_d1 = 18;
  config.pin_d2 = 19;
  config.pin_d3 = 21;
  config.pin_d4 = 36;
  config.pin_d5 = 39;
  config.pin_d6 = 34;
  config.pin_d7 = 35;
  config.pin_xclk = 0;
  config.pin_pclk = 22;
  config.pin_vsync = 25;
  config.pin_href = 23;
  config.pin_sscb_sda = 26;
  config.pin_sscb_scl = 27;
  config.pin_pwdn = 32;
  config.pin_reset = -1;
  config.xclk_freq_hz = 20000000;
  config.pixel_format = PIXFORMAT_JPEG;
  config.grab_mode = CAMERA_GRAB_LATEST;

  if (psramFound()) {
    config.frame_size = FRAMESIZE_QVGA;
    config.jpeg_quality = 63;
    config.fb_count = 2;
  } else {
    config.frame_size = FRAMESIZE_QVGA;
    config.jpeg_quality = 63;
    config.fb_count = 1;
  }

  return esp_camera_init(&config) == ESP_OK;
}

const byte halfStepSequence[8][4] = {
  {1, 0, 0, 0},
  {1, 1, 0, 0},
  {0, 1, 0, 0},
  {0, 1, 1, 0},
  {0, 0, 1, 0},
  {0, 0, 1, 1},
  {0, 0, 0, 1},
  {1, 0, 0, 1}
};

void applySpeed() {
  unsigned int fastestDelay = overdriveEnabled ? overdriveStepDelayMs : fastestStepDelayMs;
  stepDelayMs = map(speedPercent, 20, 100, slowestStepDelayMs, fastestDelay);
}

void releaseCoils() {
  for (byte coil = 0; coil < coilCount; coil++) {
    digitalWrite(coilPins[coil], LOW);
  }
}

void setCoils(byte phase) {
  for (byte coil = 0; coil < coilCount; coil++) {
    digitalWrite(coilPins[coil], halfStepSequence[phase][coil]);
  }
}

void stepMotor(int direction) {
  sequenceIndex = (sequenceIndex + direction + 8) % 8;
  setCoils(sequenceIndex);
}

void handleCommand(String command) {
  command.trim();
  command.toUpperCase();

  if (command.startsWith("GOTO ")) {
    targetPosition = command.substring(5).toInt();
    SERIAL_PRINT("Target: ");
    SERIAL_PRINTLN(targetPosition);
  } else if (command.startsWith("STEP ")) {
    targetPosition += command.substring(5).toInt();
    SERIAL_PRINT("Target: ");
    SERIAL_PRINTLN(targetPosition);
  } else if (command == "STOP") {
    targetPosition = currentPosition;
    releaseCoils();
    SERIAL_PRINTLN("Stopped");
  } else if (command == "RELEASE") {
    targetPosition = currentPosition;
    releaseCoils();
    SERIAL_PRINTLN("Coils released");
  } else if (command == "ZERO") {
    currentPosition = 0;
    targetPosition = 0;
    releaseCoils();
    SERIAL_PRINTLN("Position zeroed");
  } else if (command.startsWith("SPEED ")) {
    speedPercent = constrain(command.substring(6).toInt(), 20, 100);
    applySpeed();
    SERIAL_PRINT("Speed: ");
    SERIAL_PRINT(speedPercent);
    SERIAL_PRINTLN(" percent");
  } else if (command == "OVERDRIVE ON") {
    overdriveEnabled = true;
    applySpeed();
    SERIAL_PRINTLN("Overdrive enabled: 1 ms minimum delay");
  } else if (command == "OVERDRIVE OFF") {
    overdriveEnabled = false;
    applySpeed();
    SERIAL_PRINTLN("Overdrive disabled: 2 ms minimum delay");
  } else if (command == "STATUS") {
    SERIAL_PRINT("Position: ");
    SERIAL_PRINTLN(currentPosition);
  }
}

void readCommands() {
  while (Serial.available()) {
    char incoming = Serial.read();
    if (incoming == '\n' || incoming == '\r') {
      if (commandBuffer.length() > 0) {
        handleCommand(commandBuffer);
        commandBuffer = "";
      }
    } else {
      commandBuffer += incoming;
    }
  }
}

bool writeCameraStream(const uint8_t* data, size_t length) {
  while (length > 0) {
    size_t written = cameraStreamClient.write(data, length);
    if (written == 0) {
      return false;
    }
    data += written;
    length -= written;
  }
  return true;
}

void streamCameraFrame() {
  if (!cameraStreamClient || !cameraStreamClient.connected()) {
    WiFiClient incomingClient = cameraStreamServer.available();
    if (incomingClient) {
      incomingClient.setNoDelay(true);
      cameraStreamClient = incomingClient;
      lastFrameSentAt = 0;
      lastStreamStatsAt = millis();
      SERIAL_PRINTLN("Camera stream client connected");
    }
    return;
  }

  if (millis() - lastFrameSentAt < frameIntervalMs) {
    return;
  }
  lastFrameSentAt = millis();

  unsigned long frameStartedAt = millis();
  camera_fb_t* frame = esp_camera_fb_get();
  if (!frame) {
    SERIAL_PRINTLN("Camera capture failed");
    return;
  }

  uint32_t frameLength = frame->len;
  unsigned long sendStartedAt = millis();
  uint8_t frameLengthHeader[4] = {
    static_cast<uint8_t>((frameLength >> 24) & 0xff),
    static_cast<uint8_t>((frameLength >> 16) & 0xff),
    static_cast<uint8_t>((frameLength >> 8) & 0xff),
    static_cast<uint8_t>(frameLength & 0xff)
  };
  bool sent = writeCameraStream(frameLengthHeader, sizeof(frameLengthHeader)) &&
              writeCameraStream(frame->buf, frame->len);
  unsigned long sendDurationMs = millis() - sendStartedAt;
  unsigned long totalFrameDurationMs = millis() - frameStartedAt;
  esp_camera_fb_return(frame);

  if (!sent) {
    cameraStreamClient.stop();
    SERIAL_PRINTLN("TCP client disconnected");
  } else if (millis() - lastStreamStatsAt >= 1000) {
    SERIAL_PRINTF(
      "Camera stream: %u bytes, send %lu ms, total %lu ms\n",
      static_cast<unsigned int>(frameLength),
      sendDurationMs,
      totalFrameDurationMs
    );
    lastStreamStatsAt = millis();
  }
}

void handleCommandRequest() {
  if (!server.hasArg("value")) {
    server.send(400, "text/plain", "Missing value");
    return;
  }

  handleCommand(server.arg("value"));
  server.send(200, "text/plain", "Command accepted");
}

void handleStatus() {
  String response = "{\"position\":" + String(currentPosition) +
                    ",\"target\":" + String(targetPosition) + "}";
  server.send(200, "application/json", response);
}

void updateStepper() {
  if (currentPosition == targetPosition) {
    digitalWrite(onboardLedPin, HIGH);
    return;
  }

  unsigned long now = millis();
  if (now - lastStepAt < stepDelayMs) {
    return;
  }
  lastStepAt = now;

  bool forward = targetPosition > currentPosition;
  if (forward) {
    currentPosition++;
  } else {
    currentPosition--;
  }

  stepMotor(forward ? 1 : -1);
  digitalWrite(onboardLedPin, currentPosition % 2 ? LOW : HIGH);
}

void setup() {
  for (byte coil = 0; coil < coilCount; coil++) {
    pinMode(coilPins[coil], OUTPUT);
  }
  pinMode(onboardLedPin, OUTPUT);

  releaseCoils();
  digitalWrite(onboardLedPin, HIGH);

  Serial.begin(115200);
  bool cameraStarted = initializeCamera();
  delay(500);
  WiFi.mode(WIFI_STA);
  WiFi.begin(hotspotName, hotspotPassword);
  SERIAL_PRINT("Connecting to phone hotspot");
  unsigned long wifiStartedAt = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - wifiStartedAt < 20000) {
    delay(500);
    SERIAL_PRINT(".");
  }
  bool wifiConnected = WiFi.status() == WL_CONNECTED;
  if (wifiConnected) {
    WiFi.setSleep(false);
  }
  SERIAL_PRINTLN("");
  server.on("/cmd", handleCommandRequest);
  server.on("/status", handleStatus);
  server.begin();
  cameraStreamServer.begin();

  SERIAL_PRINTLN("28BYJ-48 ESP32-CAM controller ready");
  SERIAL_PRINT("Camera: ");
  SERIAL_PRINTLN(cameraStarted ? "OK" : "FAILED");
  SERIAL_PRINT("Wi-Fi connection: ");
  SERIAL_PRINTLN(wifiConnected ? "OK" : "FAILED");
  SERIAL_PRINTLN("Commands: GOTO n, STEP n, STOP, RELEASE, ZERO, STATUS");
  SERIAL_PRINTLN("Camera stream: TCP port 81, 4-byte big-endian JPEG length + JPEG data");
  if (wifiConnected) {
    SERIAL_PRINT("Open http://");
    SERIAL_PRINTLN(WiFi.localIP());
  }
}

void loop() {
  server.handleClient();
  streamCameraFrame();
  readCommands();
  updateStepper();
}
