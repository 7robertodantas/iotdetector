#include <WiFi.h>
#include <PubSubClient.h>

#define LED_PIN 2 // Onboard LED for most ESP32 boards
#define WIFI_SSID        "Wokwi-GUEST"
#define WIFI_PASSWORD    ""
#define BROKER_ADDR      "192.168.0.150"
#define BROKER_PORT      1883
#define BROKER_USERNAME  "usermqtt"
#define BROKER_PASSWORD  "passmqtt"
#define DEVICE_ID        "ESP32_Wokwi_01"
#define TOPIC            "aha/object_detector/d6287655-7211-46b9-8fb2-1118f38512ed/person/stat_t"
#define THRESHOLD 	  	1 // Threshold value to turn on the LED

WiFiClient espClient;
PubSubClient client(espClient);

void setup_wifi() {
  delay(10);
  Serial.println();
  Serial.print("Connecting to ");
  Serial.println(WIFI_SSID);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }
  Serial.println("");
  Serial.println("WiFi connected");
  Serial.print("IP address: ");
  Serial.println(WiFi.localIP());
}

void callback(char* topic, byte* payload, unsigned int length) {
  String msg;
  for (unsigned int i = 0; i < length; i++) {
    msg += (char)payload[i];
  }
  Serial.print("Message arrived [");
  Serial.print(topic);
  Serial.print("]: ");
  Serial.println(msg);
  int value = msg.toInt();
  if (value >= 1) {
    digitalWrite(LED_PIN, HIGH);
    Serial.println("LED ON");
  } else {
    digitalWrite(LED_PIN, LOW);
    Serial.println("LED OFF");
  }
}

void reconnect() {
  // Loop until we're reconnected
  while (!client.connected()) {
    Serial.print("Attempting MQTT connection...");
    // Attempt to connect
    if (client.connect(DEVICE_ID, BROKER_USERNAME, BROKER_PASSWORD)) {
      Serial.println("connected");
      client.subscribe(TOPIC);
    } else {
      Serial.print("failed, rc=");
      Serial.print(client.state());
      Serial.println(" try again in 5 seconds");
      delay(5000);
    }
  }
}

void setup() {
  Serial.begin(115200);
  pinMode(LED_PIN, OUTPUT);
  digitalWrite(LED_PIN, LOW);
  setup_wifi();
  client.setServer(BROKER_ADDR, BROKER_PORT);
  client.setCallback(callback);
}

void loop() {
  if (!client.connected()) {
    reconnect();
  }
  client.loop();
}
