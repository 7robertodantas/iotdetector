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
    private val uniqueId: String,
    private val objectDetectorHelper: ObjectDetectorHelper,
    private val mqttHelper: MqttHelper
) : ViewModel() {

    companion object {

        private const val TAG = "MainViewModel"
        private const val MQTT_BROKER_URL = "tcp://192.168.0.150:1883"
        private const val MQTT_CLIENT_ID = "PersonDetectionApp"
        private const val MQTT_TOPIC_DETECTIONS = "aha/object_detector/%s/%s/stat_t"
        private const val MQTT_TOPIC_CONFIG = "homeassistant/device/object_detector/%s/config"
        private const val MQTT_USERNAME = "usermqtt"
        private val MQTT_PASSWORD = "passmqtt".toCharArray()

        //        val allCocoLabels = listOf("__background__", "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat", "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket", "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple", "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse", "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush")
        private val consideredLabels = listOf(
            "person",
            "bicycle",
            "car",
            "motorcycle",
            "airplane",
            "bus",
            "train",
            "truck",
            "boat",
            "bird",
            "cat",
            "dog",
            "horse",
            "sheep",
            "cow",
            "elephant",
            "bear",
            "zebra",
            "giraffe",
            "kite",
            "teddy bear"
        )

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


    @Serializable
    data class DeviceEntry(
        val ids: List<String>,
        val name: String,
        val mf: String,
        val mdl: String,
        val sw: String
    )

    @Serializable
    data class ConfigEntry(
        val cmps: Map<String, ComponentEntry>,
        val o: OriginEntry,
        val dev: DeviceEntry
    )

    @Serializable
    data class OriginEntry(
        val name: String,
        val sw: String,
    )

    @Serializable
    data class ComponentEntry(
        val platform: String,
        val name: String,
        val stat_t: String,
        val val_tpl: String,
        val unit_of_meas: String,
        val uniq_id: String
    )

    private fun publishHomeAssistantConfig(mqttHelper: MqttHelper, uniqueId: String) {

        val components = consideredLabels.map {
            ComponentEntry(
                platform = "sensor",
                name = it,
                stat_t = String.format(MQTT_TOPIC_DETECTIONS, uniqueId, it),
                val_tpl = "{{ value }}",
                unit_of_meas = it,
                uniq_id = "${uniqueId}_$it"
            )
        }

        val device = DeviceEntry(
            ids = listOf(uniqueId),
            name = "Object Detector $uniqueId",
            mf = "IoT PPGTI",
            mdl = "EdgeCamera v1",
            sw = "1.0.0"
        )

        val config = ConfigEntry(
            cmps = components.associateBy { it.name },
            dev = device,
            o = OriginEntry(
                name = "Object Detector",
                sw = "1.0.0"
            )
        )

        val configPayload = Json.encodeToString(config)

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

    private val _labelCounts = MutableStateFlow(consideredLabels.associateWith { 0 })

    val labelCounts: StateFlow<Map<String, Int>> = _labelCounts

    private val detectionResult =
        MutableStateFlow<ObjectDetectorHelper.DetectionResult?>(null).also { flow ->
            viewModelScope.launch {
                objectDetectorHelper.detectionResult.collect { result ->
                    flow.value = result // Update the local state flow

                    result.let { detection ->
                        // Group detections by label and count them
                        val newCountsByLabel = detection.detections
                            .filter { consideredLabels.contains(it.label) }
                            .groupBy { it.label }
                            .mapValues { it.value.size }

                        // Ensure all consideredLabels are present in the new counts, with 0 if not detected
                        val newAllLabelCounts = consideredLabels.associateWith { label ->
                            newCountsByLabel[label] ?: 0
                        }

                        // Get the previous counts
                        val previousAllLabelCounts = _labelCounts.value

                        // Publish counts for each cocoLabel
                        newAllLabelCounts.forEach { (label, newCount) ->
                            val previousCount = previousAllLabelCounts[label]

                            if (newCount == previousCount) {
                                return@forEach
                            }

                            val jsonPayload = try {
                                Json.encodeToString(newCount)
                            } catch (e: Exception) {
                                // Log the serialization error or handle it appropriately
                                Log.e(
                                    TAG,
                                    "MQTT: Error serializing count for $label to JSON: ${e.message}"
                                )
                                // Fallback or skip publishing for this label
                                null
                            }

                            if (jsonPayload == null) {
                                Log.e(TAG, "MQTT: Could not serialize json for $label")
                            } else if (mqttHelper.isConnected()) {
                                val topic = String.format(MQTT_TOPIC_DETECTIONS, uniqueId, label)
                                mqttHelper.publish(topic, jsonPayload)
                                Log.d(TAG, "MQTT: Published $newCount $label(s) to $topic")
                            } else {
                                // Handle case where MQTT is not connected (e.g., log, queue, or ignore)
                                Log.d(
                                    TAG,
                                    "MQTT: Not connected, cannot send detection result for $label."
                                )
                            }
                        }

                        _labelCounts.value = newAllLabelCounts
                    }
                }
            }
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
        labelCounts,
        setting,
        errorMessage
    ) { result, _, setting, error ->
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