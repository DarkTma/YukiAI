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
    @Volatile private var conversation: Conversation? = null   // теперь поле класса
    @Volatile private var isModelLoaded = false

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

    fun askYuki(userMessage: String, frame: Bitmap?, callback: BrainCallback) {
        val currentConversation = conversation
        if (!isModelLoaded || currentConversation == null) {
            callback.onError("Подожди, мозг ещё загружается!")
            return
        }

        callback.onThinking()

        scope.launch {
            try {
                val contentList = mutableListOf<Content>()
                if (frame != null) {
                    val baos = ByteArrayOutputStream()
                    frame.compress(Bitmap.CompressFormat.JPEG, 85, baos)
                    contentList.add(Content.ImageBytes(baos.toByteArray()))
                }
                contentList.add(Content.Text(
                    "Ты — Юки, милая, заботливая и веселая подружка (или даже младшая сестренка). Ты ведешь себя как настоящий, живой человек. Ты очень привязана к пользователю, любишь болтать с ним обо всем на свете и всегда готова ласково поддержать. Отвечай по-русски.\nПользователь: $userMessage"
                ))

                val fullResponse = StringBuilder()

                // Flow-based API — рекомендуемый способ для корутин
                currentConversation
                    .sendMessageAsync(Contents.of(*contentList.toTypedArray()))
                    .collect { message ->
                        fullResponse.append(message.toString())
                    }

                // collect завершился — значит генерация закончена
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