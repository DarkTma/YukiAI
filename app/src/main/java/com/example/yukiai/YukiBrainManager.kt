package com.example.yukiai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litertlm.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags

class YukiBrainManager(private val appContext: Context, onComplete: LoadCallback?) {

    interface BrainCallback {
        fun onThinking()
        fun onResponse(text: String)
        fun onError(error: String)
    }

    interface LoadCallback {
        fun onLoaded(success: Boolean)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var engine: Engine? = null
    @Volatile private var conversation: Conversation? = null
    @Volatile private var isModelLoaded = false

    private val sceneHistory = ArrayDeque<String>()  // ограничение размера — в addToHistory
    private var messageCount = 0
    private val RESET_EVERY = 6

    private fun addToHistory(scene: String) {
        if (sceneHistory.size >= 4) sceneHistory.removeFirst()
        sceneHistory.addLast(scene)
    }

    // Пересоздать конверсацию, вшив краткое резюме прошлого
    private fun resetConversation(summary: String?) {
        conversation?.close()
        val engine = engine ?: return

        val systemText = buildString {
            append("Ты — Юки, милая и весёлая подружка. Отвечай по-русски, на 'ты', коротко.\n")
            if (summary != null) {
                append("Из того что было раньше ты помнишь: $summary\n")
            }
        }

        val config = ConversationConfig(
            samplerConfig = SamplerConfig(topK = 40, topP = 0.9, temperature = 0.9)
        )
        conversation = engine.createConversation(config)
        messageCount = 0

        // Первым сообщением вшиваем память — модель "знает" прошлое
        if (summary != null) {
            scope.launch {
                try {
                    conversation?.sendMessageAsync(
                        Contents.of(Content.Text("[Системный контекст]: $systemText"))
                    )?.collect {}
                } catch (e: Exception) {
                    Log.w("YukiBrain", "Не смог вшить контекст: ${e.message}")
                }
            }
        }
    }


    init {
        loadModel(appContext, onComplete)
    }

    private fun loadModel(context: Context, onComplete: LoadCallback?) {
        scope.launch {
            try {
                val modelFile = File(context.filesDir, "gemma4_e4b.litertlm")
                Log.e("YukiBrain", "Ищу файл: ${modelFile.absolutePath}")
                Log.e("YukiBrain", "Файл существует: ${modelFile.exists()}")
                Log.e("YukiBrain", "Файлы в filesDir: ${context.filesDir.listFiles()?.map { it.name }}")
                if (!modelFile.exists()) {
                    onComplete?.onLoaded(false)
                    return@launch
                }

                val nativeLibDir = context.applicationInfo.nativeLibraryDir
                val cacheDir = context.cacheDir.path
                val modelPath = modelFile.absolutePath

                val newEngine = tryCreateEngine(modelPath, nativeLibDir, cacheDir)
                    ?: run {
                        onComplete?.onLoaded(false)
                        return@launch
                    }

                val conversationConfig = ConversationConfig(
                    samplerConfig = SamplerConfig(topK = 40, topP = 0.9, temperature = 0.9)
                )
                conversation = newEngine.createConversation(conversationConfig)
                engine = newEngine
                isModelLoaded = true
                onComplete?.onLoaded(true)

            } catch (e: Exception) {
                Log.e("YukiBrain", "Ошибка загрузки Gemma 4", e)
                isModelLoaded = false
                onComplete?.onLoaded(false)
            }
        }
    }

    private suspend fun tryCreateEngine(
        modelPath: String,
        nativeLibDir: String,
        cacheDir: String
    ): Engine? {
        return try {
            Log.e("YukiBrain", "Пробуем CPU...")
            @OptIn(ExperimentalApi::class)
            ExperimentalFlags.enableSpeculativeDecoding = true
            val config = EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU(),
                visionBackend = Backend.CPU(),
//                maxNumTokens = 512,
                cacheDir = cacheDir
            )
            val e = Engine(config)
            e.initialize()
            Log.e("YukiBrain", "CPU: OK")
            e
        } catch (e: Exception) {
            Log.e("YukiBrain", "CPU не взлетел: ${e.message}")
            null
        }
    }

    fun reloadModel(context: Context, onComplete: LoadCallback?) {
        isModelLoaded = false
        conversation?.close()
        conversation = null
        engine?.close()
        engine = null
        loadModel(context, onComplete)
    }

    fun askYuki(userMessage: String, frame: Bitmap?, sceneDescription: String? = null, callback: BrainCallback) {
        if (!isModelLoaded || conversation == null) {
            callback.onError("Подожди, мозг ещё загружается!")
            return
        }

        callback.onThinking()

        scope.launch {
            try {
                if (messageCount >= RESET_EVERY && sceneHistory.isNotEmpty()) {
                    val summary = sceneHistory.joinToString(", затем ")
                    resetConversation(summary)
                    kotlinx.coroutines.delay(300)
                }

                val contentList = mutableListOf<Content>()

                if (frame != null) {
                    val baos = ByteArrayOutputStream()
                    frame.compress(Bitmap.CompressFormat.JPEG, 85, baos)
                    contentList.add(Content.ImageBytes(baos.toByteArray()))
                }

                // ОДИН текстовый блок — персона + история + сообщение
                val historyHint = if (sceneHistory.isNotEmpty())
                    "До этого ты видела: ${sceneHistory.takeLast(2).joinToString(", ")}. А СЕЙЧАС новый кадр. "
                else ""

                contentList.add(Content.Text(
                    "Ты — Юки, милая и весёлая подружка. Отвечай по-русски, на 'ты'.\n" +
                            "${historyHint}Пользователь: $userMessage"
                ))

                if (sceneDescription != null) addToHistory(sceneDescription)

                val fullResponse = StringBuilder()
                conversation?.sendMessageAsync(Contents.of(*contentList.toTypedArray()))
                    ?.collect { message -> fullResponse.append(message.toString()) }

                messageCount++
                callback.onResponse(fullResponse.toString().trim())

            } catch (e: Exception) {
                Log.e("YukiBrain", "Ошибка генерации", e)
                callback.onError("Мой мозг завис... ${e.message}")
            }
        }
    }



    fun shutdown() {
        scope.cancel()
        conversation?.close()
        engine?.close()
    }
}