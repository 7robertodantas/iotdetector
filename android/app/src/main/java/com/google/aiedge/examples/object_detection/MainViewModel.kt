/*
 * Copyright 2024 The Google AI Edge Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.aiedge.examples.object_detection

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.google.aiedge.examples.object_detection.mqtt.MqttHelper
import com.google.aiedge.examples.object_detection.objectdetector.ObjectDetectorHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class MainViewModel(
    private val uniqueId : String,
    private val objectDetectorHelper: ObjectDetectorHelper,
    private val mqttHelper: MqttHelper) : ViewModel() {

    companion object {

        private const val TAG = "MainViewModel"
        private const val MQTT_BROKER_URL = "tcp://192.168.0.150:1883"
        private const val MQTT_CLIENT_ID = "PersonDetectionApp"
        private const val MQTT_TOPIC_DETECTIONS = "aha/person_detector/%s_persons/stat_t"
        private const val MQTT_TOPIC_CONFIG = "homeassistant/sensor/person_detector/%s/config"
        private const val MQTT_USERNAME = "usermqtt"
        private val MQTT_PASSWORD = "passmqtt".toCharArray()

        fun getFactory(context: Context) = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                // To apply object detection, we use our ObjectDetectorHelper class,
                // which abstracts away the specifics of using MediaPipe  for object
                // detection from the UI elements of the app
                val objectDetectorHelper = ObjectDetectorHelper(context = context)

                val mqttHelper = MqttHelper(
                    context = context.applicationContext,
                    serverUri = MQTT_BROKER_URL,
                    clientId = MQTT_CLIENT_ID,
                    username = MQTT_USERNAME,
                    pass = MQTT_PASSWORD
                )
                val uniqueId = getOrCreateUniqueId(context)

                if (!mqttHelper.isConnected()) {
                    mqttHelper.connect()
                }

                return MainViewModel(uniqueId, objectDetectorHelper, mqttHelper) as T
            }
        }

        fun getOrCreateUniqueId(context: Context): String {
            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            var uniqueId = prefs.getString("unique_id", null)
            if (uniqueId == null) {
                uniqueId = java.util.UUID.randomUUID().toString()
                prefs.edit { putString("unique_id", uniqueId) }
            }
            return uniqueId
        }
    }

    private fun publishHomeAssistantConfig(mqttHelper: MqttHelper, uniqueId: String) {
        val configPayload = """
                {
                  "name": "Person Sensor",
                  "stat_t": "${String.format(MQTT_TOPIC_DETECTIONS, uniqueId)}",
                  "val_tpl": "{{ value }}",
                  "unit_of_meas": "people",
                  "uniq_id": "${uniqueId}_person_detector",
                  "device": {
                    "ids": ["$uniqueId"],
                    "name": "Person Detector $uniqueId",
                    "mf": "IoT PPGTI",
                    "mdl": "EdgeCamera v1",
                    "sw": "1.0.0"
                  }
                }
            """.trimIndent()

        val configTopic = String.format(MQTT_TOPIC_CONFIG, uniqueId)

        if (mqttHelper.isConnected()) {
            mqttHelper.publish(configTopic, configPayload, retained = true)
            Log.d(TAG, "MQTT: Home Assistant config published to $configTopic")
        } else {
            Log.w(TAG, "MQTT: Not connected, cannot publish Home Assistant config yet.")
        }
    }

    init {
        viewModelScope.launch {
            mqttHelper.isConnectedFlow
                .filter { isConnected -> isConnected }
                .first() // Pega o primeiro evento de conexão true e depois cancela a coleta (ou use take(1))
                .apply {
                    Log.d(TAG, "MQTT connection established. Publishing Home Assistant config.")
                    publishHomeAssistantConfig(mqttHelper, uniqueId)
                }
        }

        if (!mqttHelper.isConnected()) {
            mqttHelper.connect()
        }
    }


    private var detectJob: Job? = null

    private val detectionResult =
        MutableStateFlow<ObjectDetectorHelper.DetectionResult?>(null).also { flow ->
            viewModelScope.launch {
                objectDetectorHelper.detectionResult.collect { result ->
                    flow.value = result // Update the local state flow

                    result.let { detection ->
                        val persons = detection.detections.count { det -> det.label == "person" }

                        val jsonPayload = try {
                            Json.encodeToString(persons)
                        } catch (e: Exception) {
                            // Log the serialization error or handle it appropriately
                            Log.e(TAG, "MQTT: Error serializing DetectionResult to JSON: ${e.message}")
                            // Fallback or skip publishing
                            null
                        }

                        if (jsonPayload == null) {
                            Log.e(TAG, "MQTT: Could not serialize json")
                        } else if (mqttHelper.isConnected()) {
                            mqttHelper.publish(String.format(MQTT_TOPIC_DETECTIONS, uniqueId), jsonPayload)
                        } else {
                            // Handle case where MQTT is not connected (e.g., log, queue, or ignore)
                            Log.d(TAG, "MQTT: Not connected, cannot send detection result.")
                        }
                    }

                }
            }
        }

    private fun formatDetectionResultForMqtt(result: ObjectDetectorHelper.DetectionResult): String {
        val res =
            DetectionJson(
                result.detections.map { det ->
                    DetectionJsonEntry(
                        name = det.label,
                        confidence = det.score
                    )
                }
            )

        val jsonString = Json.encodeToString(res)
        return jsonString
    }

    private val setting = MutableStateFlow(Setting())
        .apply {
            viewModelScope.launch {
                collect {
                    objectDetectorHelper.apply {
                        model = it.model
                        delegate = it.delegate
                        maxResults = it.resultCount
                        threshold = it.threshold
                    }
                    objectDetectorHelper.setupObjectDetector()
                }
            }
        }

    private val errorMessage = MutableStateFlow<Throwable?>(null).also {
        viewModelScope.launch {
            objectDetectorHelper.error.collect(it)
        }
    }

    val uiState: StateFlow<UiState> = combine(
        detectionResult,
        setting,
        errorMessage
    ) { result, setting, error ->
        UiState(
            detectionResult = result,
            setting = setting,
            errorMessage = error?.message
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    /**
     *  Start detect object from an image.
     *  @param bitmap Tries to make a new bitmap based on the dimensions of this bitmap,
     *  @param rotationDegrees to correct the rotationDegrees during segmentation
     */
    fun detectImageObject(bitmap: Bitmap, rotationDegrees: Int) {
        detectJob = viewModelScope.launch {
            objectDetectorHelper.detect(bitmap, rotationDegrees)
        }
    }

    fun detectImageObject(imageProxy: ImageProxy) {
        detectJob = viewModelScope.launch {
            objectDetectorHelper.detect(imageProxy)
            imageProxy.close()
        }
    }

    /** Set [ObjectDetectorHelper.Delegate] (CPU/GPU) for ObjectDetectorHelper*/
    fun setDelegate(delegate: ObjectDetectorHelper.Delegate) {
        viewModelScope.launch {
            setting.update { it.copy(delegate = delegate) }
        }
    }

    /** Set Number of output classes of the ObjectDetectorHelper.  */
    fun setNumberOfResult(numResult: Int) {
        viewModelScope.launch {
            setting.update { it.copy(resultCount = numResult) }
        }
    }

    /** Set the threshold so the label can display score */
    fun setThreshold(threshold: Float) {
        viewModelScope.launch {
            setting.update { it.copy(threshold = threshold) }
        }
    }

    /** Stop current detection */
    fun stopDetect() {
        viewModelScope.launch {
            detectionResult.emit(null)
            detectJob?.cancel()
        }
    }

    /** Clear error message after it has been consumed*/
    fun errorMessageShown() {
        errorMessage.update { null }
    }

    // Disconnect MQTT when ViewModel is cleared
    override fun onCleared() {
        super.onCleared()
        mqttHelper.disconnect()
    }

    @Serializable
    data class DetectionJson(
        val objects: List<DetectionJsonEntry>
    )

    @Serializable
    data class DetectionJsonEntry(
        val name: String,
        val confidence: Float
    )
}