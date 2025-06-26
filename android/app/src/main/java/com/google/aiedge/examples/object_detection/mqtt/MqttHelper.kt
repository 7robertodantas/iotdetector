package com.google.aiedge.examples.object_detection.mqtt

import android.content.Context
import android.util.Log
import info.mqtt.android.service.MqttAndroidClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage

class MqttHelper(context: Context,
                 serverUri: String,
                 clientId: String = MqttClient.generateClientId(),
                 private val username: String, // Add username
                 private val pass: CharArray  // Add password (use CharArray for security)
) {

    companion object {
        private const val TAG = "MqttHelper"
    }

    private val _isConnected = MutableStateFlow(false)
    val isConnectedFlow: StateFlow<Boolean> = _isConnected.asStateFlow()
    private var mqttClient: MqttAndroidClient = MqttAndroidClient(context.applicationContext, serverUri, clientId)

    init {
        mqttClient.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                Log.d(TAG, "Connected to: $serverURI")
                // You can subscribe to topics here if needed
            }

            override fun connectionLost(cause: Throwable?) {
                Log.w(TAG, "Connection lost: ${cause?.message}")
            }

            override fun messageArrived(topic: String?, message: MqttMessage?) {
                // Not used for publishing-only client, but required by interface
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {
                Log.d(TAG, "Message delivered")
            }
        })
        connect()
    }

    fun connect() {
        val mqttConnectOptions = MqttConnectOptions().apply {
            isAutomaticReconnect = true
            isCleanSession = true // Set to false if you want persistent sessions

            Log.d(TAG, "MqttHelper connect() - Username before setting: '${this.userName}'")
            this.userName = username
            this.password = pass
            Log.d(TAG, "MqttHelper connect() - Username after setting: '${this.userName}'")
        }
        try {
            mqttClient.connect(mqttConnectOptions, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    _isConnected.value = true
                    Log.d(TAG, "Connection success")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    _isConnected.value = false
                    Log.e(TAG, "Connection failed: ${exception?.message}")
                }
            })
        } catch (e: MqttException) {
            Log.e(TAG, "Error connecting: ${e.message}")
        }
    }

    fun publish(topic: String, message: String, qos: Int = 1, retained: Boolean = false) {
        if (!mqttClient.isConnected) {
            Log.w(TAG, "MQTT client not connected. Attempting to connect.")
            // You might want to queue messages or handle this more robustly
            connect() // Try to reconnect
            return
        }

        try {
            val mqttMessage = MqttMessage(message.toByteArray()).apply {
                this.qos = qos
                this.isRetained = retained
            }
            mqttClient.publish(topic, mqttMessage, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.d(TAG, "Message published to topic '$topic'")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(TAG, "Failed to publish message to topic '$topic': ${exception?.message}")
                }
            })
        } catch (e: MqttException) {
            Log.e(TAG, "Error publishing message: ${e.message}")
        }
    }

    fun disconnect() {
        try {
            if (mqttClient.isConnected) {
                mqttClient.disconnect()
                Log.d(TAG, "Disconnected")
            }
        } catch (e: MqttException) {
            Log.e(TAG, "Error disconnecting: ${e.message}")
        }
    }

    fun isConnected(): Boolean {
        return mqttClient.isConnected
    }
}