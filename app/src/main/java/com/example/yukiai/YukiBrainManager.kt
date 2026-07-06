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
                Log.e("YukiBrain", "Файлы в filesDir: ${context.filesDir.listFiles()?.map { it.name }}")

                val cacheDir = context.cacheDir.path

                val newEngine = tryCreateEngine(context.filesDir, cacheDir)
                    ?: run {
                        Log.e("YukiBrain", "❌ Ни одна конфигурация движка не завелась")
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

    // Одна попытка создания движка: какой файл модели + какие бэкенды.
    // Бэкенды создаём лямбдами, чтобы для каждой попытки был свежий объект.
    private data class EngineAttempt(
        val modelFileName: String,
        val llmBackend: () -> Backend,
        val visionBackend: () -> Backend,
        val label: String
    )

    private suspend fun tryCreateEngine(filesDir: File, cacheDir: String): Engine? {
        @OptIn(ExperimentalApi::class)
        ExperimentalFlags.enableSpeculativeDecoding = true

        // Каскад: от самого умного/быстрого к самому надёжному.
        // Первая же успешная конфигурация выигрывает. Логи покажут, какая именно.
        val attempts = listOf(
            // E4B умнее, но на GPU капризна (issue #1206). Зрение на CPU — частый рабочий вариант.
            EngineAttempt("gemma4_e4b.litertlm", { Backend.GPU() }, { Backend.CPU() }, "E4B / GPU-LLM + CPU-vision"),
            // Всё на GPU — если вдруг зрение на GPU тоже поедет.
            EngineAttempt("gemma4_e4b.litertlm", { Backend.GPU() }, { Backend.GPU() }, "E4B / GPU-LLM + GPU-vision"),
            // E2B меньше и на GPU заводится чаще — быстрый и всё ещё мультимодальный.
            EngineAttempt("gemma4_e2b.litertlm", { Backend.GPU() }, { Backend.CPU() }, "E2B / GPU-LLM + CPU-vision"),
            // Финальный fallback — как было раньше, гарантированно работает.
            EngineAttempt("gemma4_e4b.litertlm", { Backend.CPU() }, { Backend.CPU() }, "E4B / CPU (fallback)")
        )

        for (a in attempts) {
            val f = File(filesDir, a.modelFileName)
            if (!f.exists()) {
                Log.e("YukiBrain", "Пропускаю «${a.label}»: нет файла ${a.modelFileName}")
                continue
            }
            val e = buildEngine(f.absolutePath, cacheDir, a.llmBackend(), a.visionBackend(), a.label)
            if (e != null) {
                Log.e("YukiBrain", "✅ АКТИВНАЯ КОНФИГУРАЦИЯ: ${a.label}")
                return e
            }
        }
        return null
    }

    private fun buildEngine(
        modelPath: String,
        cacheDir: String,
        backend: Backend,
        visionBackend: Backend,
        label: String
    ): Engine? {
        return try {
            Log.e("YukiBrain", "Пробуем $label...")
            val config = EngineConfig(
                modelPath = modelPath,
                backend = backend,
                visionBackend = visionBackend,
//                maxNumTokens = 512,
                cacheDir = cacheDir
            )
            val e = Engine(config)
            e.initialize()
            Log.e("YukiBrain", "$label: OK")
            e
        } catch (e: Exception) {
            Log.e("YukiBrain", "$label не взлетел: ${e.message}")
            e.printStackTrace()
            null
        }
    }

//    private suspend fun tryCreateEngine(
//        modelPath: String,
//        nativeLibDir: String,
//        cacheDir: String
//    ): Engine? {
//        try {
//            // Обязательно предупреждаем коллбэком интерфейс, что сейчас будет долго
//            Log.e("YukiBrain", "🚀 ЗАПУСК NPU! Сейчас будет JIT-компиляция. Это может занять до 2 минут...")
//
//            @OptIn(ExperimentalApi::class)
//            ExperimentalFlags.enableSpeculativeDecoding = true
//
//            val npuConfig = EngineConfig(
//                modelPath = modelPath, // Твой обычный скачанный файл на 2.5 ГБ
//                backend = Backend.NPU(), // Требуем NPU
//                visionBackend = Backend.GPU(), // Зрение лучше оставить на GPU
//
//                // ВОТ ЭТО САМОЕ ВАЖНОЕ: Сюда сохранится граф после первой долгой загрузки!
//                cacheDir = cacheDir
//            )
//
//            // Во время вызова engine.initialize() телефон зависнет на компиляции.
//            // Не пугайся, если Android предложит "Закрыть приложение или подождать".
//            // Обязательно жми "Подождать"!
//            val engine = Engine(npuConfig)
//            engine.initialize()
//
//            Log.e("YukiBrain", "✅ NPU УСПЕШНО СКОМПИЛИРОВАН И СОХРАНЕН В КЭШ!")
//            return engine
//
//        } catch (e: Exception) {
//            Log.e("YukiBrain", "❌ NPU не вывез JIT-компиляцию: ${e.message}")
//            e.printStackTrace()
//            return null
//        }
//    }

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